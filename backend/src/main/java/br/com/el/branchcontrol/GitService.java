package br.com.el.branchcontrol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static br.com.el.branchcontrol.Models.*;

@Service
public class GitService {
    private static final Logger LOG = LoggerFactory.getLogger(GitService.class);
    private final GitRunner runner;
    private final GitPatchEquivalence equivalence;
    private final ZoneId zone;
    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    public GitService(GitRunner runner, AppProperties properties) {
        this.runner = runner;
        this.equivalence = new GitPatchEquivalence(runner);
        this.zone = ZoneId.of(properties.zoneId());
    }

    GitRunner runner() { return runner; }
    ReentrantLock lock(Path repository) { return locks.computeIfAbsent(repository, ignored -> new ReentrantLock()); }

    public Path resolveRepository(String input) {
        Path path = realPath(input);
        if (!Files.isDirectory(path)) throw AppException.bad("Informe uma pasta de repositório Git.");
        var result = runner.run(path, "rev-parse", "--show-toplevel");
        if (result.code() != 0) throw AppException.bad("A pasta informada não pertence a um repositório Git.");
        Path root = realPath(result.output().strip());
        if ("true".equals(runner.require(root, "rev-parse", "--is-bare-repository").output().strip())) {
            throw AppException.bad("É necessário um checkout com diretório de trabalho.");
        }
        return root;
    }

    public RepositoryView open(String input) {
        Path repo = resolveRepository(input);
        String current = runner.require(repo, "branch", "--show-current").output().strip();
        String head = runner.require(repo, "rev-parse", "HEAD").output().strip();
        String changes = runner.require(repo, "status", "--porcelain=v1", "--untracked-files=no").output().stripTrailing();
        return new RepositoryView(repo.toString(), current, head, !changes.isBlank(),
                changes.isBlank() ? List.of() : changes.lines().toList(), operation(repo), branches(repo));
    }

    private List<Branch> branches(Path repo) {
        String result = runner.require(repo, "for-each-ref", "--format=%(refname)%09%(objectname)",
                "refs/heads", "refs/remotes").output();
        var list = new ArrayList<Branch>();
        for (String line : result.lines().toList()) {
            String[] parts = line.split("\t", 2);
            if (parts.length != 2 || parts[0].endsWith("/HEAD")) continue;
            boolean remote = parts[0].startsWith("refs/remotes/");
            String prefix = remote ? "refs/remotes/" : "refs/heads/";
            list.add(new Branch(parts[0].substring(prefix.length()), parts[0], parts[1], remote));
        }
        list.sort(Comparator.comparing(Branch::remote).thenComparing(Branch::name));
        return List.copyOf(list);
    }

    private Branch branch(Path repo, String name, boolean localOnly) {
        if (name == null || name.isBlank() || name.startsWith("-") || name.length() > 300) {
            throw AppException.bad("Selecione uma branch válida.");
        }
        List<Branch> matches = branches(repo).stream().filter(item -> (!localOnly || !item.remote())
                && (name.startsWith("refs/") ? item.ref().equals(name) : item.name().equals(name))).toList();
        if (matches.isEmpty()) throw AppException.bad("Branch não encontrada: " + name);
        if (matches.size() != 1) throw AppException.bad("Nome de branch ambíguo. Selecione uma referência completa: refs/heads/... ou refs/remotes/...");
        return matches.get(0);
    }

    public Plan prepare(String input, String sourceName, String targetName, LocalDate cutoff) {
        if (cutoff == null) throw AppException.bad("Informe a data limite.");
        Path repo = resolveRepository(input);
        Branch source = branch(repo, sourceName, false);
        Branch target = branch(repo, targetName, true);
        Instant exclusiveEnd = cutoff.plusDays(1).atStartOfDay(zone).toInstant();
        String output = runner.require(repo, "log", "--reverse", "--topo-order", "--no-merges",
                "--right-only", "--format=%H%x09%cI%x09%s", target.head() + "..." + source.head()).output();
        var commits = new ArrayList<Commit>();
        for (String line : output.lines().toList()) {
            if (line.isBlank()) continue;
            Commit commit = parseCommit(line);
            if (OffsetDateTime.parse(commit.date()).toInstant().isBefore(exclusiveEnd)) commits.add(commit);
        }
        List<Commit> pending = equivalence.pending(repo, target.head(), commits);
        LOG.info("Prévia: repositório={}, origem={}, destino={}, limite={}, commits={}",
                repo, source.name(), target.name(), cutoff, pending.size());
        return createPlan(repo, source, target, cutoff, pending);
    }

    private Plan createPlan(Path repo, Branch source, Branch target, LocalDate cutoff, List<Commit> commits) {
        return new Plan(UUID.randomUUID().toString(), repo.toString(), source.ref(), target.name(),
                source.head(), target.head(), cutoff.toString(), zone.getId(), Instant.now().toString(), List.copyOf(commits));
    }

    static Commit parseCommit(String line) {
        String[] fields = line.split("\t", 3);
        if (fields.length != 3) throw new AppException(422, "Formato inesperado na resposta do Git.");
        return new Commit(fields[0], fields[1], fields[2]);
    }

    Commit readCommit(Path repo, String hash) {
        if (hash == null || !hash.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) {
            throw AppException.bad("O TXT contém um hash inválido.");
        }
        runner.require(repo, "cat-file", "-e", hash + "^{commit}");
        String parents = runner.require(repo, "show", "-s", "--format=%P", hash).output().strip();
        if (parents.contains(" ")) throw AppException.bad("Commits de merge exigem tratamento manual: " + hash);
        return parseCommit(runner.require(repo, "show", "-s", "--format=%H%x09%cI%x09%s", hash).output().stripTrailing());
    }

    public List<Commit> selected(Plan plan, Collection<String> hashes) {
        if (hashes == null || hashes.isEmpty()) throw AppException.bad("Selecione pelo menos um commit.");
        if (hashes.size() > 500) throw AppException.bad("Selecione até 500 commits por execução.");
        var selected = new HashSet<>(hashes);
        var known = new HashSet<>(plan.commits().stream().map(Commit::hash).toList());
        if (!known.containsAll(selected)) throw AppException.bad("A seleção contém commits que não pertencem à prévia. Atualize a lista.");
        return plan.commits().stream().filter(commit -> selected.contains(commit.hash())).toList();
    }

    public String pickState(Path repo, String hash, String upstream) {
        var ancestor = runner.run(repo, "merge-base", "--is-ancestor", hash, upstream);
        if (ancestor.code() == 0) return "PRESENT";
        if (ancestor.code() != 1) throw new AppException(422, "Falha ao verificar a ancestralidade do commit.");
        var result = runner.require(repo, "cherry", upstream, hash).output().lines().toList();
        if (result.contains("- " + hash)) return "EQUIVALENT";
        if (result.contains("+ " + hash)) return "PENDING";
        throw new AppException(422, "Não foi possível classificar o commit " + hash);
    }

    public String operation(Path repo) {
        for (String name : List.of("CHERRY_PICK_HEAD", "MERGE_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer")) {
            Path path = Path.of(runner.require(repo, "rev-parse", "--path-format=absolute", "--git-path", name).output().strip());
            if (Files.exists(path)) return name;
        }
        return "";
    }

    public void validateExecution(Plan plan) {
        Path repo = resolveRepository(plan.repositoryPath());
        RepositoryView view = open(repo.toString());
        if (!view.currentBranch().equals(plan.targetBranch())) throw AppException.conflict("Ative a branch de destino " + plan.targetBranch() + " antes de aplicar.");
        if (!view.head().equals(plan.targetHead())) throw AppException.conflict("O HEAD mudou depois da prévia. Gere a lista novamente.");
        if (!view.operation().isEmpty()) throw AppException.conflict("Existe uma operação Git em andamento: " + view.operation() + ".");
        if (view.dirtyTracked()) throw AppException.conflict("Existem alterações locais em arquivos versionados. Guarde em commit ou stash antes de aplicar.");
    }

    public void validateStep(Path repo, String expectedBranch, String expectedHead) {
        RepositoryView view = open(repo.toString());
        if (!view.currentBranch().equals(expectedBranch) || !view.head().equals(expectedHead)) {
            throw AppException.conflict("A branch ou o HEAD foi alterado por outro processo. A execução foi interrompida.");
        }
        if (!view.operation().isEmpty() || view.dirtyTracked()) throw AppException.conflict("O estado local mudou. Confira git status antes de continuar.");
    }

    public RepositoryView switchBranch(String input, String targetName) {
        Path repo = resolveRepository(input);
        ReentrantLock lock = lock(repo);
        if (!lock.tryLock()) throw AppException.conflict("Há uma operação em andamento neste repositório.");
        try {
            Branch target = branch(repo, targetName, true);
            RepositoryView view = open(repo.toString());
            if (view.dirtyTracked() || !view.operation().isBlank()) throw AppException.conflict("Guarde as alterações e finalize operações Git antes de trocar de branch.");
            runner.require(repo, "switch", target.name());
            LOG.info("Branch ativa alterada: repositório={}, destino={}", repo, target.name());
            return open(repo.toString());
        } finally { lock.unlock(); }
    }

    public RepositoryView fetch(String input) {
        Path repo = resolveRepository(input);
        ReentrantLock lock = lock(repo);
        if (!lock.tryLock()) throw AppException.conflict("Há uma operação em andamento neste repositório.");
        try {
            runner.require(repo, "fetch", "--no-tags", "origin");
            LOG.info("Fetch concluído: repositório={}", repo);
            return open(repo.toString());
        } finally { lock.unlock(); }
    }

    public String exportText(Plan plan, Collection<String> hashes) {
        StringBuilder out = new StringBuilder("# gpi-cherry-pick-v1\r\n");
        out.append("# repositorio=").append(plan.repositoryPath().replace('\\', '/')).append("\r\n");
        out.append("# destino=").append(plan.targetBranch()).append("\r\n");
        out.append("# head-destino=").append(plan.targetHead()).append("\r\n");
        out.append("# origem=").append(plan.sourceBranch()).append("\r\n");
        out.append("# head-origem=").append(plan.sourceHead()).append("\r\n");
        out.append("# data-limite=").append(plan.cutoff()).append("\r\n");
        out.append("data\tnome\thash\r\n");
        for (Commit commit : selected(plan, hashes)) {
            out.append(commit.date()).append('\t').append(commit.message().replace('\t', ' ').replace('\r', ' ').replace('\n', ' '))
                    .append('\t').append(commit.hash()).append("\r\n");
        }
        return out.toString();
    }

    public Plan importText(String input, String targetName, String content) {
        if (content == null || content.length() > 2_000_000) throw AppException.bad("Informe um TXT com até 2 MB.");
        Path repo = resolveRepository(input);
        Branch target = branch(repo, targetName, true);
        String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
        var lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).equals("# gpi-cherry-pick-v1")) throw AppException.bad("Formato TXT não reconhecido.");
        Map<String, String> metadata = new HashMap<>();
        Set<String> hashes = new LinkedHashSet<>();
        boolean header = false;
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (line.startsWith("# ")) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    String key = line.substring(2, separator);
                    if (metadata.putIfAbsent(key, line.substring(separator + 1)) != null) throw AppException.bad("Metadado repetido no TXT: " + key);
                }
            } else if (line.equals("data\tnome\thash")) {
                header = true;
            } else {
                if (!header) throw AppException.bad("Cabeçalho do TXT ausente.");
                int separator = line.lastIndexOf('\t');
                if (separator < 0) throw AppException.bad("Linha de commit inválida.");
                hashes.add(line.substring(separator + 1).toLowerCase(Locale.ROOT));
            }
        }
        if (!header) throw AppException.bad("Cabeçalho do TXT ausente.");
        if (!target.name().equals(metadata.get("destino"))) throw AppException.bad("O TXT pertence a outra branch de destino.");
        if (!repo.equals(resolveRepository(metadata.get("repositorio")))) throw AppException.bad("O TXT pertence a outro repositório.");
        Branch source = branch(repo, metadata.get("origem"), false);
        LocalDate cutoff;
        try { cutoff = LocalDate.parse(metadata.get("data-limite")); }
        catch (RuntimeException ex) { throw AppException.bad("Data limite inválida no TXT."); }
        if (hashes.size() > 500) throw AppException.bad("Importe até 500 commits por vez.");
        Instant end = cutoff.plusDays(1).atStartOfDay(zone).toInstant();
        for (String hash : hashes) {
            Commit commit = readCommit(repo, hash);
            if (!OffsetDateTime.parse(commit.date()).toInstant().isBefore(end)) throw AppException.bad("O TXT contém um commit posterior à data limite.");
            if (runner.run(repo, "merge-base", "--is-ancestor", hash, source.head()).code() != 0) throw AppException.bad("Commit não pertence à branch de origem: " + hash);
        }
        // Canonical Git order, independent of the order of lines edited in the TXT.
        Plan canonical = prepare(repo.toString(), source.ref(), target.name(), cutoff);
        return new Plan(canonical.id(), canonical.repositoryPath(), canonical.sourceBranch(), canonical.targetBranch(),
                canonical.sourceHead(), canonical.targetHead(), canonical.cutoff(), canonical.zoneId(), canonical.createdAt(),
                canonical.commits().stream().filter(commit -> hashes.contains(commit.hash())).toList());
    }

    private static Path realPath(String value) {
        if (value == null || value.isBlank()) throw AppException.bad("Informe o caminho do repositório.");
        try { return Path.of(value).toRealPath(); }
        catch (IOException | RuntimeException ex) { throw AppException.bad("Pasta não encontrada ou sem permissão de leitura."); }
    }
}
