package br.com.el.branchcontrol;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static br.com.el.branchcontrol.Models.*;

@Service
@DependsOn("databaseInstanceLease")
public class RunService implements AutoCloseable {
    private static final Actor INTERNAL = new Actor("internal", "interno", Groups.ADM);
    private final GitService git;
    private final Path logDirectory, simulationsDirectory;
    private final ExecutionStore store;
    private final EnvironmentStore environments;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "branch-operation"); thread.setDaemon(true); return thread;
    });
    private final ConcurrentMap<String, Execution> runs = new ConcurrentHashMap<>();
    public record StoredExecution(Plan plan, RunView run) {}

    RunService(GitService git, AppProperties properties, ExecutionStore store) { this(git,properties,store,null,null); }
    RunService(GitService git, AppProperties properties, ExecutionStore store, EnvironmentStore environments) { this(git,properties,store,environments,null); }
    @Autowired public RunService(GitService git, AppProperties properties, ExecutionStore store, EnvironmentStore environments,DatabaseInstanceLease lease) {
        this.git = git;
        this.store = store;
        this.environments = environments;
        if(lease!=null)lease.onLoss(executor::shutdownNow);
        logDirectory = Path.of(properties.logDirectory()).toAbsolutePath().normalize();
        simulationsDirectory = logDirectory.resolveSibling("simulations");
        try {
            Files.createDirectories(logDirectory);
            Files.createDirectories(simulationsDirectory);
            for (StoredExecution saved : store.load()) {
                    Execution run = new Execution(saved);
                    runs.put(run.id, run);
                    if (!run.terminal()) run.finish("INTERRUPTED", "Aplicação reiniciada. Confira git status; nenhuma operação foi retomada automaticamente.");
            }
        } catch (IOException | RuntimeException ex) { throw new IllegalStateException("Não foi possível carregar o histórico do PostgreSQL. Confira a conexão com o banco.", ex); }
    }

    // Package-private entry points are used only by local Git fixture tests.
    RunView start(Plan plan, Collection<String> hashes, boolean simulation) { return start(plan, hashes, simulation, INTERNAL); }
    RunView get(String id) { return get(id, INTERNAL); }
    public synchronized RunView start(Plan plan, Collection<String> hashes, boolean simulation, Actor actor) {
        if (!List.of(Groups.USER, Groups.ADM).contains(actor.group())) throw new AppException(403, "Sua conta não pode executar commits.");
        if (environments != null) environments.validatePlan(actor,plan,simulation?Permissions.SIMULATE:Permissions.APPLY);
        List<Commit> selection = git.selected(plan, hashes);
        if (!List.copyOf(hashes).equals(selection.stream().map(Commit::hash).toList())) {
            throw AppException.bad("Envie cada hash uma única vez, na ordem da prévia.");
        }
        if (!simulation) {
            if (!Groups.ADM.equals(actor.group()) && !hasSimulation(plan, selection, actor)) {
                throw new AppException(403, "Simule esta mesma seleção de commits sem erros antes de aplicar.");
            }
            git.validateExecution(plan);
        }
        if (runs.values().stream().filter(run -> !run.terminal()).count() >= 100) throw new AppException(429, "Há muitas operações em andamento. Aguarde.");
        String id = UUID.randomUUID().toString();
        Execution run = new Execution(id, plan, selection, simulation, actor);
        run.persist(); run.log("Execução " + id + " criada."); runs.put(id, run);
        executor.submit(() -> execute(run, selection));
        return run.view(true);
    }

    private boolean hasSimulation(Plan plan, List<Commit> selection, Actor actor) {
        List<String> hashes = selection.stream().map(Commit::hash).toList();
        return runs.values().stream().anyMatch(run -> {
            RunView view = run.view(false);
            return view.simulation() && view.state().equals("COMPLETED") && view.actor().userId().equals(actor.userId())
                    && run.plan.equals(plan) && view.commits().stream().map(CommitProgress::hash).toList().equals(hashes);
        });
    }

    private void execute(Execution run, List<Commit> selection) {
        ReentrantLock lock = null;
        boolean acquired = false;
        Path sandbox = null;
        try {
            if(environments!=null) environments.validatePlan(run.actor,run.plan,run.simulation?Permissions.SIMULATE:Permissions.APPLY);
            Path repo = git.resolveRepository(run.plan.repositoryPath());
            lock = git.lock(repo); acquired = lock.tryLock();
            if (!acquired) { run.finish("FAILED", "Há outra operação em andamento neste repositório."); return; }
            run.state("RUNNING");
            run.log("Responsável: " + run.actor.username() + " / " + run.actor.group());
            run.log("Repositório: " + repo);
            run.log("Origem: " + run.plan.sourceBranch() + " [" + run.plan.sourceHead() + "]");
            run.log("Destino: " + run.plan.targetBranch() + " [" + run.plan.targetHead() + "]");
            run.log("Simulação: " + run.simulation + "; commits selecionados: " + selection.size());
            Path working = repo;
            if (run.simulation) {
                sandbox = simulationsDirectory.resolve(run.id);
                git.runner().require(simulationsDirectory, "clone", "--shared", "--no-checkout", "--", repo.toString(), sandbox.toString());
                git.runner().require(sandbox, "config", "user.name", "Branch Control Simulation");
                git.runner().require(sandbox, "config", "user.email", "simulation@branch-control.invalid");
                git.runner().require(sandbox, "config", "core.hooksPath", simulationsDirectory.resolve("no-hooks").toString());
                git.runner().require(sandbox, "config", "commit.gpgsign", "false");
                git.runner().require(sandbox, "checkout", "--detach", run.plan.targetHead());
                working = sandbox;
                run.log("Simulação em cópia temporária isolada; checkout original preservado.");
            } else git.validateExecution(run.plan);
            String expectedHead = run.plan.targetHead();
            for (Commit commit : selection) {
                if (!run.simulation) git.validateStep(repo, run.plan.targetBranch(), expectedHead);
                String state = git.pickState(working, commit.hash(), "HEAD");
                run.log(state + ": " + commit.hash() + " | " + commit.message());
                if (!state.equals("PENDING")) { run.progress(commit, state, ""); continue; }
                var result = git.runner().run(working, "cherry-pick", "--no-gpg-sign", commit.hash());
                result.combined().lines().filter(line -> !line.isBlank()).forEach(run::log);
                if (result.code() != 0) {
                    boolean conflict = !git.operation(working).isEmpty();
                    run.progress(commit, conflict ? "CONFLICT" : "FAILED", "");
                    run.finish(conflict ? "CONFLICT" : "FAILED", run.simulation
                            ? "Simulação interrompida. O checkout original está preservado. Corrija a seleção ou as dependências e simule novamente."
                            : "Execução interrompida. Confira git status. Resolva os arquivos e use git add e git cherry-pick --continue. Depois gere outra prévia. git cherry-pick --abort cancela somente o commit em andamento; os anteriores permanecem.");
                    return;
                }
                expectedHead = git.runner().require(working, "rev-parse", "HEAD").output().strip();
                run.progress(commit, run.simulation ? "WILL_APPLY" : "APPLIED", run.simulation ? "" : expectedHead);
                run.log((run.simulation ? "VALIDADO: " : "APLICADO: ") + commit.hash() + (run.simulation ? "" : " -> " + expectedHead));
            }
            run.finish("COMPLETED", run.simulation ? "Simulação concluída sem conflitos. HEAD original preservado." : "Aplicação concluída. Nenhum push foi executado.");
        } catch (Exception ex) {
            run.finish("FAILED", ex.getMessage() == null ? "Falha na execução. Confira git status." : ex.getMessage());
        } finally {
            if (sandbox != null) {
                try { removeSandbox(sandbox); }
                catch (IOException ex) {
                    try { run.log("Não foi possível limpar a cópia temporária " + sandbox + ". Remova-a após fechar processos que estejam usando essa pasta."); }
                    catch (RuntimeException ignored) { }
                }
            }
            if (acquired) lock.unlock();
        }
    }

    private void removeSandbox(Path path) throws IOException {
        Path root = path.toAbsolutePath().normalize();
        if (!root.getParent().equals(simulationsDirectory) || !root.getFileName().toString().matches("[a-f0-9-]{36}")) throw new IOException("Pasta temporária inválida.");
        if (Files.exists(root)) try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isSymbolicLink(file)) file.toFile().setWritable(true);
                Files.delete(file);
            }
        }
    }

    static boolean visible(Actor viewer, Actor owner) {
        return Groups.ADM.equals(viewer.group()) || viewer.userId().equals(owner.userId());
    }
    private Execution require(String id, Actor actor) {
        Execution run = runs.get(id);
        if (run == null || !(environments==null?visible(actor,run.actor):environments.canRead(actor,run.view(false)))) throw new AppException(404, "Execução não encontrada.");
        return run;
    }
    public RunView get(String id, Actor actor) { return require(id, actor).view(true); }
    public List<RunView> list(Actor actor) {
        return runs.values().stream().filter(run -> environments==null?visible(actor,run.actor):environments.canRead(actor,run.view(false))).map(run -> run.view(false))
                .sorted(Comparator.comparing(RunView::startedAt).reversed()).toList();
    }
    public List<RunView> reports(Actor actor) {
        return runs.values().stream().map(run -> run.view(false)).filter(run -> environments==null?visible(actor,run.actor()):environments.canReport(actor,run))
                .sorted(Comparator.comparing(RunView::startedAt).reversed()).toList();
    }
    public String log(String id, Actor actor) {
        require(id, actor);
        return store.log(id);
    }
    public record PushPreview(String runId, String remote, String targetBranch, String head) {}

    private Execution ownedRealRun(String id, Actor actor) {
        Execution run = require(id, actor);
        if (!visible(actor, run.actor)) throw new AppException(404,"Execução não encontrada.");
        if (run.simulation || !run.terminal()) throw AppException.conflict("Aguarde uma execução real terminar.");
        return run;
    }
    private String appliedHead(Execution run) {
        String head = run.plan.targetHead();
        for (CommitProgress commit : run.view(false).commits()) if (commit.state().equals("APPLIED")) head = commit.newHash();
        return head;
    }
    private Plan continued(Execution run, List<Commit> commits) {
        Plan p = run.plan;
        return new Plan(UUID.randomUUID().toString(),p.repositoryPath(),p.sourceBranch(),p.targetBranch(),p.sourceHead(),
                appliedHead(run),p.cutoff(),p.zoneId(),Instant.now().toString(),List.copyOf(commits),p.environmentId(),p.environmentRevision());
    }
    private void validateContinuation(Execution run, Actor actor, String permission, boolean clean) {
        if(environments!=null) environments.validatePlan(actor,run.plan,permission);
        var view = git.open(run.plan.repositoryPath());
        if (!view.currentBranch().equals(run.plan.targetBranch()) || !view.head().equals(appliedHead(run)))
            throw AppException.conflict("A branch ou o HEAD mudou depois da execução. Gere uma nova lista.");
        if(clean && (view.dirtyTracked() || !view.operation().isEmpty()))
            throw AppException.conflict("Guarde as alterações e finalize operações Git antes do push.");
    }
    public Plan remaining(String id, Actor actor) {
        Execution run = ownedRealRun(id,actor);
        Path repo = git.resolveRepository(run.plan.repositoryPath());
        ReentrantLock lock = git.lock(repo);
        if(!lock.tryLock()) throw AppException.conflict("Há uma operação em andamento neste repositório.");
        try {
            validateContinuation(run,actor,Permissions.PREPARE,false);
            Set<String> resolved = new HashSet<>();
            for(CommitProgress commit:run.view(false).commits())
                if(List.of("APPLIED","PRESENT","EQUIVALENT").contains(commit.state())) resolved.add(commit.hash());
            return continued(run,run.plan.commits().stream().filter(commit->!resolved.contains(commit.hash())).toList());
        } finally { lock.unlock(); }
    }
    private PushPreview validatePush(Execution run, Actor actor) {
        var view = run.view(false);
        if (!view.state().equals("COMPLETED") || view.commits().stream().noneMatch(commit->commit.state().equals("APPLIED")))
            throw AppException.conflict("O push exige uma aplicação concluída com commits aplicados.");
        validateContinuation(run,actor,Permissions.APPLY,true);
        Path repo = git.resolveRepository(run.plan.repositoryPath());
        // Validate presence without returning credential-bearing remote URLs.
        if(git.runner().run(repo,"remote","get-url","--push","origin").code()!=0)
            throw AppException.conflict("Configure o remoto origin antes do push.");
        return new PushPreview(run.id,"origin",run.plan.targetBranch(),appliedHead(run));
    }
    public PushPreview pushPreview(String id, Actor actor) {
        Execution run=ownedRealRun(id,actor);
        ReentrantLock lock=git.lock(git.resolveRepository(run.plan.repositoryPath()));
        if(!lock.tryLock()) throw AppException.conflict("Há uma operação em andamento neste repositório.");
        try { return validatePush(run,actor); } finally { lock.unlock(); }
    }
    public PushPreview push(String id, String expectedHead, Actor actor) {
        Execution run=ownedRealRun(id,actor);
        Path repo=git.resolveRepository(run.plan.repositoryPath());
        ReentrantLock lock=git.lock(repo);
        if(!lock.tryLock()) throw AppException.conflict("Há uma operação em andamento neste repositório.");
        try {
            PushPreview preview=validatePush(run,actor);
            if(!preview.head().equals(expectedHead)) throw AppException.conflict("O HEAD confirmado não corresponde à execução.");
            run.log("PUSH SOLICITADO: origin / "+preview.targetBranch()+" ["+preview.head()+"] por "+actor.username());
            try {
                git.runner().require(repo,"-c","push.followTags=false","push","--porcelain","--no-follow-tags",
                        "--recurse-submodules=no","--","origin",preview.head()+":refs/heads/"+preview.targetBranch());
                run.log("PUSH CONCLUÍDO: origin / "+preview.targetBranch()+" ["+preview.head()+"]");
                return preview;
            } catch(RuntimeException ex) {
                run.log("PUSH FALHOU: origin / "+preview.targetBranch()+". Confira o remoto antes de tentar novamente.");
                throw new AppException(ex instanceof AppException app?app.status():422,"Push não confirmado. Confira o remoto origin e suas permissões antes de tentar novamente.");
            }
        } finally { lock.unlock(); }
    }
    @Override @PreDestroy public void close() { executor.shutdownNow(); }

    private final class Execution {
        final String id;
        final Plan plan;
        final Actor actor;
        final boolean simulation;
        final Path logPath;
        final String startedAt;
        final String environmentName;
        private String state, finishedAt;
        private final List<CommitProgress> commits = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();
        Execution(String id, Plan plan, List<Commit> selection, boolean simulation, Actor actor) {
            this.id = id; this.plan = plan; this.actor = actor; this.simulation = simulation;
            environmentName=environments==null?"":environments.name(plan.environmentId());
            logPath = logDirectory.resolve(id + ".log"); startedAt = Instant.now().toString(); state = "QUEUED"; finishedAt = "";
            for (Commit commit : selection) commits.add(new CommitProgress(commit.hash(), commit.message(), "PENDING", ""));
        }
        Execution(StoredExecution snapshot) {
            RunView saved = snapshot.run(); id = saved.id(); plan = snapshot.plan(); actor = saved.actor();
            simulation = saved.simulation(); logPath = logDirectory.resolve(id + ".log"); startedAt = saved.startedAt();
            state = saved.state(); finishedAt = saved.finishedAt(); commits.addAll(saved.commits());
            environmentName=saved.environmentName()==null?"Histórico legado":saved.environmentName();
            lines.addAll(store.tail(id));
        }
        synchronized void persist() {
            store.save(new StoredExecution(plan, view(false)));
        }
        synchronized void state(String value) { state = value; persist(); }
        synchronized void progress(Commit commit, String value, String newHash) {
            for (int i = 0; i < commits.size(); i++) if (commits.get(i).hash().equals(commit.hash())) {
                commits.set(i, new CommitProgress(commit.hash(), commit.message(), value, newHash)); persist(); return;
            }
        }
        synchronized void log(String message) {
            String line = "[" + Instant.now() + "] " + message;
            try { Files.writeString(logPath, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException ex) { throw new AppException(500, "Não foi possível gravar o log. Confira o estado do Git."); }
            store.appendLog(id, line);
            lines.add(line); if (lines.size() > 2000) lines.remove(0);
        }
        synchronized void finish(String value, String message) {
            state = value; finishedAt = Instant.now().toString();
            try { log(message); persist(); }
            catch (RuntimeException ex) { lines.add("Falha ao gravar: " + ex.getMessage()); state = "FAILED"; }
        }
        synchronized boolean terminal() { return !List.of("QUEUED", "RUNNING").contains(state); }
        synchronized RunView view(boolean includeLines) {
            return new RunView(id, state, simulation, plan.repositoryPath(), plan.sourceBranch(), plan.targetBranch(),
                    startedAt, finishedAt, logPath.toString(), List.copyOf(commits), includeLines ? List.copyOf(lines) : List.of(), actor, plan.id(),plan.environmentId(),environmentName);
        }
    }
}
