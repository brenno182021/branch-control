package br.com.el.branchcontrol;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import static br.com.el.branchcontrol.UserModels.*;

@Component
@DependsOn("databaseInstanceLease")
public class LegacyImport {
    public LegacyImport(DataSource source, UsersStore users, ExecutionStore executions,
            @Value("${branch-control.import-legacy:false}") boolean enabled,
            @Value("${branch-control.legacy-directory:./data}") String directory) {
        importFrom(source, users, executions, enabled, Path.of(directory));
    }
    static void importFrom(DataSource source, UsersStore users, ExecutionStore executions, boolean enabled, Path directory) {
        Path root = directory.toAbsolutePath().normalize(), userFile = root.resolve("users.json"), history = root.resolve("history");
        var jdbc = new JdbcTemplate(source);
        if (jdbc.queryForObject("SELECT count(*) FROM bc_legacy_import WHERE source='json-v1'", Long.class) > 0) return;
        try {
            List<Account> accounts = List.of();
            if (Files.exists(userFile)) {
                if (Files.size(userFile) > 2_000_000) throw new IOException("users.json excede 2 MB.");
                UsersFile data = AtomicJsonFile.read(userFile, UsersFile.class);
                if (data == null || data.version() != 1 || data.users() == null) throw new IOException("Formato de usuários inválido.");
                accounts = data.users().stream().map(account -> {
                    String role = switch (account.group()) {
                        case "RHF-ADM", "ADM" -> Groups.ADM;
                        case "RHF-TESTE", "RHF-DEV", "USER" -> Groups.USER;
                        default -> throw AppException.bad("Papel legado não reconhecido.");
                    };
                    return new Account(account.id(),account.username(),account.name(),account.passwordHash(),role,account.enabled(),account.revision(),account.createdAt(),account.updatedAt());
                }).toList();
                var ids = new HashSet<String>(); var names = new HashSet<String>();
                for (var account : accounts) {
                    UsersStore.validate(account);
                    if (!ids.add(account.id()) || !names.add(account.username())) throw new IOException("Usuário legado duplicado.");
                }
                if (!accounts.isEmpty() && accounts.stream().noneMatch(a -> a.enabled() && a.group().equals(Groups.ADM))) throw new IOException("Legado precisa ter pelo menos um ADM ativo.");
            }
            var snapshots = new ArrayList<RunService.Snapshot>();
            var logs = new HashMap<String,List<String>>();
            if (Files.isDirectory(history)) try (var files = Files.list(history)) {
                for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    if (Files.size(file) > 4_000_000) throw new IOException("Histórico legado excede o limite.");
                    var snapshot = AtomicJsonFile.read(file, RunService.Snapshot.class); ExecutionStore.validate(snapshot);
                    if (!file.getFileName().toString().equals(snapshot.run().id() + ".json")) throw new IOException("Identificador de histórico inválido.");
                    snapshots.add(snapshot);
                    Path log = root.resolve("logs").resolve(snapshot.run().id() + ".log");
                    if (Files.isRegularFile(log)) {
                        if (Files.size(log) > 64L * 1024 * 1024) throw new IOException("Log legado maior que 64 MB; preserve o arquivo e importe manualmente.");
                        logs.put(snapshot.run().id(), Files.readAllLines(log, StandardCharsets.UTF_8));
                    }
                }
            }
            if (!enabled) {
                if ((!accounts.isEmpty() && users.needsSetup()) || (!snapshots.isEmpty() && jdbc.queryForObject("SELECT count(*) FROM bc_execution", Long.class) == 0))
                    throw new IllegalStateException("Dados JSON anteriores encontrados. Faça backup e configure BRANCH_CONTROL_IMPORT_LEGACY=true para migrá-los explicitamente.");
                return;
            }
            if (accounts.isEmpty() && snapshots.isEmpty()) return;
            final List<Account> validatedAccounts = List.copyOf(accounts);
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT name FROM bc_lock WHERE name='users' FOR UPDATE", String.class);
                if (jdbc.queryForObject("SELECT count(*) FROM bc_legacy_import WHERE source='json-v1'", Long.class) > 0) return;
                if (!users.needsSetup() || jdbc.queryForObject("SELECT count(*) FROM bc_execution", Long.class) > 0) throw new IllegalStateException("Importação legada exige banco novo, sem usuários ou execuções. Nenhum dado será mesclado ou sobrescrito.");
                validatedAccounts.forEach(users::insert);
                for (var snapshot : snapshots) {
                    executions.save(snapshot);
                    for (String line : logs.getOrDefault(snapshot.run().id(), List.of())) executions.appendLog(snapshot.run().id(), line);
                }
                jdbc.update("INSERT INTO bc_legacy_import(source,users_count,executions_count) VALUES('json-v1',?,?)", validatedAccounts.size(), snapshots.size());
            });
        } catch (IOException | RuntimeException ex) { throw new IllegalStateException("Importação JSON recusada; preserve os arquivos de origem e confira os dados/conexão. Nenhuma credencial foi redefinida.", ex); }
    }
}
