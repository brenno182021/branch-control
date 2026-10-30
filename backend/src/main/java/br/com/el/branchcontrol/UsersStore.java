package br.com.el.branchcontrol;

import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static br.com.el.branchcontrol.UserModels.*;

public final class UsersStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder encoder;
    private static final RowMapper<Account> ROW = (rs, index) -> new Account(rs.getString("id"), rs.getString("username"),
        rs.getString("name"), rs.getString("password_hash"), rs.getString("user_group"), rs.getBoolean("enabled"),
        rs.getLong("revision"), rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString());

    public UsersStore(DataSource source, PasswordEncoder encoder) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.encoder = encoder;
    }
    private <T> T locked(Supplier<T> operation) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT name FROM bc_lock WHERE name='users' FOR UPDATE", String.class);
            return operation.get();
        });
    }
    public boolean needsSetup() { return jdbc.queryForObject("SELECT count(*) FROM bc_user", Long.class) == 0; }
    public List<UserView> list() { return jdbc.query("SELECT * FROM bc_user ORDER BY created_at, id", ROW).stream().map(Account::view).toList(); }
    public Optional<Account> findById(String id) {
        if (id == null || !id.matches("[a-fA-F0-9-]{36}")) return Optional.empty();
        return jdbc.query("SELECT * FROM bc_user WHERE id=CAST(? AS uuid)", ROW, id).stream().findFirst();
    }
    public Optional<Account> findByUsername(String raw) {
        String username;
        try { username = normalizeUsername(raw); } catch (AppException ex) { return Optional.empty(); }
        return jdbc.query("SELECT * FROM bc_user WHERE username=?", ROW, username).stream().findFirst();
    }
    public UserView setupAdmin(String username, String name, String password) {
        return locked(() -> {
            if (!needsSetup()) throw AppException.conflict("A configuração inicial já foi realizada.");
            return createInside(username, name, password, Groups.ADM);
        });
    }
    public UserView create(String username, String name, String password, String group) {
        return locked(() -> createInside(username, name, password, group));
    }
    private UserView createInside(String rawUsername, String rawName, String password, String group) {
        String username = normalizeUsername(rawUsername), name = validName(rawName);
        Groups.require(group); validPassword(password);
        if (findByUsername(username).isPresent()) throw AppException.conflict("Já existe um usuário com esse login.");
        String now = Instant.now().toString();
        Account account = new Account(UUID.randomUUID().toString(), username, name, encoder.encode(password), group, true, 1, now, now);
        insert(account); return account.view();
    }
    public UserView update(String id, String rawName, String group, boolean enabled, String password) {
        return locked(() -> {
            Account current = findById(id).orElseThrow(() -> new AppException(404, "Usuário não encontrado."));
            String name = validName(rawName); Groups.require(group);
            if (current.enabled() && Groups.ADM.equals(current.group()) && (!enabled || !Groups.ADM.equals(group)) &&
                jdbc.queryForObject("SELECT count(*) FROM bc_user WHERE enabled AND user_group='ADM'", Long.class) == 1) {
                throw AppException.conflict("A última conta ADM ativa não pode ser desativada ou rebaixada.");
            }
            String hash = current.passwordHash();
            if (password != null && !password.isEmpty()) { validPassword(password); hash = encoder.encode(password); }
            Account updated = new Account(current.id(), current.username(), name, hash, group, enabled,
                Math.addExact(current.revision(), 1), current.createdAt(), Instant.now().toString());
            jdbc.update("UPDATE bc_user SET name=?, password_hash=?, user_group=?, enabled=?, revision=?, updated_at=CAST(? AS timestamptz) WHERE id=CAST(? AS uuid)",
                updated.name(), updated.passwordHash(), updated.group(), updated.enabled(), updated.revision(), updated.updatedAt(), updated.id());
            return updated.view();
        });
    }
    void insert(Account account) {
        validate(account);
        jdbc.update("INSERT INTO bc_user(id,username,name,password_hash,user_group,enabled,revision,created_at,updated_at) VALUES(CAST(? AS uuid),?,?,?,?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz))",
            account.id(), account.username(), account.name(), account.passwordHash(), account.group(), account.enabled(),
            account.revision(), account.createdAt(), account.updatedAt());
    }
    static void validate(Account account) {
        UUID.fromString(account.id()); Groups.require(account.group()); validName(account.name());
        if (!account.username().equals(normalizeUsername(account.username())) || account.revision() < 1 ||
            account.passwordHash() == null || !account.passwordHash().matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")) throw AppException.bad("Registro legado de usuário inválido.");
        int cost = Integer.parseInt(account.passwordHash().substring(4,6));
        if (cost < 4 || cost > 14) throw AppException.bad("Custo BCrypt inválido.");
        Instant.parse(account.createdAt()); Instant.parse(account.updatedAt());
    }
    static String normalizeUsername(String value) {
        if (value == null) throw AppException.bad("Informe o login.");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{1,39}")) throw AppException.bad("Use de 2 a 40 letras, números, ponto, hífen ou sublinhado no login.");
        return normalized;
    }
    private static String validName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 80 || value.chars().anyMatch(Character::isISOControl))
            throw AppException.bad("Informe um nome com até 80 caracteres, sem caracteres de controle.");
        return value.strip();
    }
    static void validPassword(String value) {
        if (value == null || value.codePointCount(0,value.length()) < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw AppException.bad("A senha deve ter no mínimo 8 caracteres e no máximo 72 bytes em UTF-8.");
    }
}
