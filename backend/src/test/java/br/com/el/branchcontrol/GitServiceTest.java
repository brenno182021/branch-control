package br.com.el.branchcontrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitServiceTest {
    @TempDir Path workspace;
    Path repository;
    GitRunner runner;
    GitService service;
    AppProperties properties;
    ExecutionStore executionStore;
    TestDatabase database;
    String base;
    String first;
    String second;
    String equivalent;
    String future;

    @BeforeEach void prepare() throws Exception {
        repository = Files.createDirectories(workspace.resolve("repo com espaços & teste"));
        runner = new GitRunner(30);
        database = new TestDatabase();
        executionStore = new ExecutionStore(database.source);
        properties = new AppProperties(workspace.resolve("logs").toString(),
                "America/Sao_Paulo", 30);
        service = new GitService(runner, properties);
        command("init", "-q", "--initial-branch=destino");
        command("config", "user.name", "Teste local");
        command("config", "user.email", "teste@example.invalid");
        command("config", "commit.gpgsign", "false");
        command("config", "core.autocrlf", "false");
        command("config", "core.hooksPath", workspace.resolve("sem-hooks").toString());
        base = commit("base.txt", "base\n", "base", "2026-10-20T12:00:00-03:00");
        command("switch", "-q", "-c", "draft/rhf/rhf-dev");
        first = commit("base.txt", "origem\n", "feat(rhf): Convênio & teste !", "2026-10-22T12:00:00-03:00");
        second = commit("base.txt", "origem\nsegunda\n", "segunda", "2026-10-23T23:59:59-03:00");
        equivalent = commit("rateio.txt", "corrigido\n", "rateio", "2026-10-24T12:00:00-03:00");
        future = commit("futuro.txt", "futuro\n", "futuro", "2026-10-25T00:00:00-03:00");
        command("switch", "-q", "destino");
    }

    String command(String... arguments) { return runner.require(repository, arguments).output().strip(); }

    String commit(String file, String contents, String title, String date) throws Exception {
        Files.writeString(repository.resolve(file), contents, StandardCharsets.UTF_8);
        command("add", file);
        runner.require(repository, Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date),
                "commit", "-q", "-m", title);
        return command("rev-parse", "HEAD");
    }

    Models.Plan plan(String limit) {
        return service.prepare(repository.toString(), "draft/rhf/rhf-dev", "destino", LocalDate.parse(limit));
    }

    @Test void filtersInclusiveCutoffAndKeepsAncestorOrder() {
        var preview = plan("2026-10-23");
        assertEquals(List.of(first, second), preview.commits().stream().map(Models.Commit::hash).toList());
        assertEquals("feat(rhf): Convênio & teste !", preview.commits().get(0).message());
        assertEquals(base, command("rev-parse", "HEAD"));
    }

    @Test void excludesEquivalentEvenWhenDestinationCommitIsNewerThanCutoff() {
        runner.require(repository, Map.of("GIT_COMMITTER_DATE", "2026-10-29T12:00:00-03:00"),
                "cherry-pick", equivalent);
        assertEquals(List.of(first, second), plan("2026-10-24").commits().stream().map(Models.Commit::hash).toList());
        assertEquals("EQUIVALENT", service.pickState(repository, equivalent, "HEAD"));
    }

    @Test void exportAndImportRoundTripAndValidateEveryHash() {
        var preview = plan("2026-10-24");
        String content = service.exportText(preview, List.of(first, second));
        var imported = service.importText(repository.toString(), "destino", content);
        assertEquals(List.of(first, second), imported.commits().stream().map(Models.Commit::hash).toList());
        assertThrows(AppException.class, () -> service.importText(repository.toString(), "destino",
                content.replace(second, "f".repeat(40))));
        assertEquals(base, command("rev-parse", "HEAD"));
    }

    @Test void rejectsNonRepositoryAndShellLikeRefs() throws Exception {
        assertThrows(AppException.class, () -> service.open(workspace.getParent().toString()));
        assertThrows(AppException.class, () -> service.prepare(repository.toString(),
                "--help", "destino", LocalDate.now()));
        assertThrows(AppException.class, () -> service.prepare(repository.toString(),
                "draft/rhf/rhf-dev; echo inseguro", "destino", LocalDate.now()));
        assertEquals(base, command("rev-parse", "HEAD"));
    }

    @Test void detectsDirtyAndInProgressWithoutDiscardingFiles() throws Exception {
        Files.writeString(repository.resolve("base.txt"), "trabalho local\n");
        assertTrue(service.open(repository.toString()).dirtyTracked());
        assertThrows(AppException.class, () -> service.validateExecution(plan("2026-10-24")));
        assertEquals("trabalho local\n", Files.readString(repository.resolve("base.txt")));
    }

    @Test void rejectsStalePlanAndWrongActiveBranch() {
        var preview = plan("2026-10-24");
        command("cherry-pick", equivalent);
        assertThrows(AppException.class, () -> service.validateExecution(preview));
        var current = plan("2026-10-24");
        command("switch", "-q", "draft/rhf/rhf-dev");
        assertThrows(AppException.class, () -> service.validateExecution(current));
    }

    @Test void rejectsAmbiguousShortRefAndKeepsCanonicalSource() {
        command("branch", "origin/demo", base);
        command("update-ref", "refs/remotes/origin/demo", future);
        assertThrows(AppException.class, () -> service.prepare(repository.toString(),
                "origin/demo", "destino", LocalDate.parse("2026-10-23")));
        var preview = service.prepare(repository.toString(), "refs/remotes/origin/demo",
                "destino", LocalDate.parse("2026-10-23"));
        assertEquals("refs/remotes/origin/demo", preview.sourceBranch());
        assertEquals(List.of(first, second), preview.commits().stream().map(Models.Commit::hash).toList());
        var imported = service.importText(repository.toString(), "destino",
                service.exportText(preview, List.of(first, second)));
        assertEquals("refs/remotes/origin/demo", imported.sourceBranch());
    }

    @Test void simulationDoesNotChangeHeadAndExecutionIsRepeatSafe() throws Exception {
        var preview = plan("2026-10-24");
        try (var jobs = new RunService(service, properties, executionStore)) {
            var dev = new Models.Actor("dev-id", "dev", Groups.USER);
            assertThrows(AppException.class, () -> jobs.start(preview, List.of(first, second), false, dev));
            var simulation = await(jobs, jobs.start(preview, List.of(first, second), true, dev).id());
            assertEquals("COMPLETED", simulation.state());
            assertThrows(AppException.class,()->jobs.remaining(simulation.id(),dev));
            assertThrows(AppException.class,()->jobs.pushPreview(simulation.id(),dev));
            assertEquals(base, command("rev-parse", "HEAD"));
            assertThrows(AppException.class, () -> jobs.start(preview, List.of(second), false, dev));
            assertThrows(AppException.class, () -> jobs.start(preview, List.of(second, first), false, dev));
            assertThrows(AppException.class, () -> jobs.start(preview, List.of(first, second, first), false, dev));
            assertThrows(AppException.class, () -> jobs.start(preview, List.of(first, second), false,
                    new Models.Actor("other-id", "outro", Groups.USER)));
            var execution = await(jobs, jobs.start(preview, List.of(first, second), false, dev).id());
            assertEquals("COMPLETED", execution.state(), String.join("\n", execution.lines()));
            assertEquals("origem\nsegunda\n", Files.readString(repository.resolve("base.txt")));
            assertFalse(Files.exists(repository.resolve("futuro.txt")));
            var after = command("rev-parse", "HEAD");
            var refreshed = plan("2026-10-24");
            assertEquals(List.of(equivalent), refreshed.commits().stream().map(Models.Commit::hash).toList());
            assertThrows(AppException.class, () -> jobs.start(refreshed, List.of(first), false));
            assertEquals(after, command("rev-parse", "HEAD"));
            assertTrue(Files.isRegularFile(Path.of(execution.logPath())));
        }
    }

    @Test void failedSimulationPreservesOriginalAndCannotAuthorizeDev() throws Exception {
        commit("base.txt", "destino diferente\n", "local", "2026-10-26T12:00:00-03:00");
        var preview = plan("2026-10-24");
        String before = command("rev-parse", "HEAD");
        var dev = new Models.Actor("dev-id", "dev", Groups.USER);
        String id;
        try (var jobs = new RunService(service, properties, executionStore)) {
            var result = await(jobs, jobs.start(preview, List.of(first, second), true, dev).id());
            id = result.id();
            assertEquals("CONFLICT", result.state(), String.join("\n", result.lines()));
            assertEquals(before, command("rev-parse", "HEAD"));
            assertEquals("", service.operation(repository));
            assertEquals("destino diferente\n", Files.readString(repository.resolve("base.txt")));
            assertThrows(AppException.class, () -> jobs.start(preview, List.of(first, second), false, dev));
            assertEquals(0, jobs.list(new Models.Actor("teste-id", "teste", Groups.USER)).size());
            assertThrows(AppException.class, () -> jobs.get(id, new Models.Actor("other", "outro", Groups.USER)));
        }
        try (var restored = new RunService(service, properties, executionStore)) {
            assertEquals("CONFLICT", restored.get(id, dev).state());
            assertFalse(restored.log(id, dev).isBlank());
        }
    }

    @Test void conflictStopsRemainingCommitsAndPreservesGitState() throws Exception {
        commit("base.txt", "destino diferente\n", "local", "2026-10-26T12:00:00-03:00");
        var preview = plan("2026-10-24");
        var before = command("rev-parse", "HEAD");
        try (var jobs = new RunService(service, properties, executionStore)) {
            var failed = await(jobs, jobs.start(preview, List.of(first, second, equivalent), false).id());
            assertEquals("CONFLICT", failed.state(), String.join("\n", failed.lines()));
            assertEquals(before, command("rev-parse", "HEAD"));
            assertEquals(first, command("rev-parse", "CHERRY_PICK_HEAD"));
            assertEquals(preview.commits(),jobs.remaining(failed.id(),new Models.Actor("internal","interno",Groups.ADM)).commits());
            assertThrows(AppException.class,()->jobs.pushPreview(failed.id(),new Models.Actor("internal","interno",Groups.ADM)));
            assertFalse(Files.exists(repository.resolve("rateio.txt")));
            assertThrows(AppException.class, () -> service.validateExecution(preview));
        }
    }

    Models.RunView await(RunService jobs, String id) throws Exception {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
        Models.RunView result;
        do {
            result = jobs.get(id);
            if (!List.of("QUEUED", "RUNNING").contains(result.state())) return result;
            Thread.sleep(30);
        } while (System.nanoTime() < until);
        fail("Operação não terminou: " + result);
        return result;
    }

    @Test void continuationKeepsUnselectedAndPushRequiresExplicitExactHead() throws Exception {
        var actor = new Models.Actor("owner", "owner", Groups.ADM);
        var preview = plan("2026-10-24");
        Path remote = workspace.resolve("origin.git");
        runner.require(workspace,"init","--bare",remote.toString());
        command("remote","add","origin",remote.toString());
        command("push","origin","destino");
        try(var jobs = new RunService(service,properties,executionStore)) {
            var run = await(jobs,jobs.start(preview,List.of(first),false,actor).id());
            var remaining = jobs.remaining(run.id(),actor);
            assertEquals(List.of(second,equivalent),remaining.commits().stream().map(Models.Commit::hash).toList());
            assertNotEquals(preview.id(),remaining.id());
            assertEquals(preview.sourceHead(),remaining.sourceHead());
            String head = command("rev-parse","HEAD");
            assertEquals(head,remaining.targetHead());
            assertEquals(base,runner.require(remote,"rev-parse","refs/heads/destino").output().strip());
            assertEquals(head,jobs.pushPreview(run.id(),actor).head());
            assertThrows(AppException.class,()->jobs.push(run.id(),base,actor));
            assertThrows(AppException.class,()->jobs.remaining(run.id(),new Models.Actor("stranger","other",Groups.USER)));
            jobs.push(run.id(),head,actor);
            assertEquals(head,runner.require(remote,"rev-parse","refs/heads/destino").output().strip());
            assertTrue(jobs.log(run.id(),actor).contains("PUSH CONCLUÍDO"));
            String remoteCommit=runner.require(remote,"-c","user.name=Fixture","-c","user.email=fixture@example.invalid",
                    "commit-tree",head+"^{tree}","-p",head,"-m","remote extra").output().strip();
            runner.require(remote,"update-ref","refs/heads/destino",remoteCommit);
            assertThrows(AppException.class,()->jobs.push(run.id(),head,actor));
            assertEquals(remoteCommit,runner.require(remote,"rev-parse","refs/heads/destino").output().strip());
            assertEquals(head,command("rev-parse","HEAD"));
            assertTrue(jobs.log(run.id(),actor).contains("PUSH FALHOU"));
            Files.writeString(repository.resolve("untracked.txt"),"local\n");
            assertEquals(head,jobs.pushPreview(run.id(),actor).head());
            assertEquals("local\n",Files.readString(repository.resolve("untracked.txt")));
            assertNotEquals(0,runner.run(remote,"cat-file","-e",remoteCommit+":untracked.txt").code());
            Files.delete(repository.resolve("untracked.txt"));
            Files.writeString(repository.resolve("base.txt"),"dirty\n");
            assertThrows(AppException.class,()->jobs.pushPreview(run.id(),actor));
            assertEquals(remaining.commits(),jobs.remaining(run.id(),actor).commits());
        }
    }

    @Test void restoredContinuationAndPushRecheckCurrentRightsRevisionAndOwnership() throws Exception {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(database.source);
        String ownerId=java.util.UUID.randomUUID().toString(), readerId=java.util.UUID.randomUUID().toString();
        for(String id:List.of(ownerId,readerId)) jdbc.update("INSERT INTO bc_user(id,username,name,password_hash,user_group,enabled,revision,created_at,updated_at) VALUES(CAST(? AS uuid),?,?,?,'USER',true,1,current_timestamp,current_timestamp)",id,id,id,"$2a$04$"+"a".repeat(53));
        var actor=new Models.Actor(ownerId,ownerId,Groups.USER);
        var reader=new Models.Actor(readerId,readerId,Groups.USER);
        var admin=new Models.Actor("admin","admin",Groups.ADM);
        var environments=new EnvironmentStore(database.source,service);
        var env=environments.save(null,new EnvironmentStore.Change("Fixture",repository.toString(),List.of("*"),List.copyOf(Permissions.ALL),List.of(ownerId,readerId),true),admin);
        var preview=plan("2026-10-24").inEnvironment(env.id(),env.revision());
        command("cherry-pick",first);
        String head=command("rev-parse","HEAD"), id=java.util.UUID.randomUUID().toString();
        String now=java.time.Instant.now().toString();
        var saved=new Models.RunView(id,"COMPLETED",false,preview.repositoryPath(),preview.sourceBranch(),preview.targetBranch(),now,now,
                workspace.resolve("logs").resolve(id+".log").toString(),List.of(new Models.CommitProgress(first,"first","APPLIED",head)),List.of(),actor,preview.id(),env.id(),env.name());
        executionStore.save(new RunService.StoredExecution(preview,saved));
        Path remote=workspace.resolve("origin.git"); runner.require(workspace,"init","--bare",remote.toString()); command("remote","add","origin",remote.toString());
        try(var jobs=new RunService(service,properties,executionStore,environments)) {
            var remaining=jobs.remaining(id,actor);
            assertEquals(List.of(second,equivalent),remaining.commits().stream().map(Models.Commit::hash).toList());
            assertEquals(head,jobs.pushPreview(id,actor).head());
            assertEquals(404,assertThrows(AppException.class,()->jobs.pushPreview(id,reader)).status());
            assertEquals(404,assertThrows(AppException.class,()->jobs.remaining(id,reader)).status());
            assertThrows(AppException.class,()->jobs.start(remaining,List.of(second),false,actor));
            jdbc.update("DELETE FROM bc_environment_permission WHERE environment_id=CAST(? AS uuid) AND permission='APPLY'",env.id());
            assertEquals(403,assertThrows(AppException.class,()->jobs.push(id,head,actor)).status());
            jdbc.update("INSERT INTO bc_environment_permission VALUES(CAST(? AS uuid),'APPLY')",env.id());
            jdbc.update("UPDATE bc_environment SET revision=revision+1 WHERE id=CAST(? AS uuid)",env.id());
            assertEquals(409,assertThrows(AppException.class,()->jobs.remaining(id,actor)).status());
            assertEquals(409,assertThrows(AppException.class,()->jobs.pushPreview(id,actor)).status());
        }
    }
}
