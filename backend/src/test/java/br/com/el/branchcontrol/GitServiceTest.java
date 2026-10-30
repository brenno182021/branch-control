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
    String base;
    String first;
    String second;
    String equivalent;
    String future;

    @BeforeEach void prepare() throws Exception {
        repository = Files.createDirectories(workspace.resolve("repo com espaços & teste"));
        runner = new GitRunner(30);
        executionStore = new ExecutionStore(new TestDatabase().source);
        properties = new AppProperties(workspace.toString(), workspace.resolve("logs").toString(),
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

    @Test void refusesOutsideWorkspaceAndShellLikeRefs() throws Exception {
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
}
