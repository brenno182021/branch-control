package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GitRunnerPipelineTest {
    @TempDir Path repo;
    static final List<String> BLOCKED = List.of("hash-object", "--stdin");
    static final List<String> PATCH_ID = List.of("patch-id", "--stable");

    @Test void reportsFailureOfEitherNativeProcess() {
        var runner = new GitRunner(5);
        assertEquals(422, assertThrows(AppException.class, () -> runner.requirePipeline(repo,
                List.of("not-a-git-command"), PATCH_ID)).status());
        assertEquals(422, assertThrows(AppException.class, () -> runner.requirePipeline(repo,
                List.of("version"), List.of("not-a-git-command"))).status());
        assertEquals("", runner.requirePipeline(repo, List.of("version"), PATCH_ID).output());
        runner.require(repo, "init", "-q");
        AppException error = assertThrows(AppException.class, () -> runner.requirePipeline(repo,
                List.of("remote", "get-url", "https://user:private-password@example.invalid/repo"), PATCH_ID));
        assertEquals(422, error.status());
        assertFalse(error.getMessage().contains("private-password"));
        assertTrue(error.getMessage().contains("https://***@example.invalid"));
    }

    @Test void timeoutTerminatesBothProcessesWithinOneDeadline() {
        var observed = new AtomicReference<List<ProcessHandle>>(List.of());
        var checks = new AtomicInteger();
        var runner = new GitRunner(5, () -> {
            if (checks.incrementAndGet() == 2) observed.set(gitChildren());
        });
        assertTimeoutPreemptively(Duration.ofSeconds(9), () ->
                assertEquals(408, assertThrows(AppException.class, () -> runner.requirePipeline(repo, BLOCKED, PATCH_ID)).status()));
        assertTrue(observed.get().size() >= 2, "Both Git stages (and Windows dispatch children) must be observed");
        assertStopped(observed.get());
    }

    @Test void leasePreflightLossAfterStartupTerminatesBothProcesses() {
        var checks = new AtomicInteger();
        var observed = new AtomicReference<List<ProcessHandle>>(List.of());
        var runner = new GitRunner(30, () -> {
            if (checks.incrementAndGet() == 2) {
                observed.set(gitChildren());
                throw new AppException(503, "Lease lost");
            }
        });
        assertThrows(AppException.class, () -> runner.requirePipeline(repo, BLOCKED, PATCH_ID));
        assertTrue(observed.get().size() >= 2, "Both Git stages (and Windows dispatch children) must be observed");
        assertStopped(observed.get());
    }

    @Test void abortActiveTerminatesBothNativeStages() throws Exception {
        var ready = new CountDownLatch(1);
        var checks = new AtomicInteger();
        var observed = new AtomicReference<List<ProcessHandle>>(List.of());
        var runner = new GitRunner(30, () -> {
            if (checks.incrementAndGet() == 2) { observed.set(gitChildren()); ready.countDown(); }
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> assertThrows(AppException.class,
                    () -> runner.requirePipeline(repo, BLOCKED, PATCH_ID)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            runner.abortActive();
            running.get(5, TimeUnit.SECONDS);
            assertTrue(observed.get().size() >= 2, "Both Git stages (and Windows dispatch children) must be observed");
            assertStopped(observed.get());
        } finally { runner.abortActive(); executor.shutdownNow(); }
    }

    @Test void interruptionRetainsInterruptStatusAndTerminatesBothStages() throws Exception {
        var ready = new CountDownLatch(1);
        var checks = new AtomicInteger();
        var observed = new AtomicReference<List<ProcessHandle>>(List.of());
        var interrupted = new AtomicBoolean();
        var runner = new GitRunner(30, () -> {
            if (checks.incrementAndGet() == 2) { observed.set(gitChildren()); ready.countDown(); }
        });
        Thread thread = new Thread(() -> {
            try { runner.requirePipeline(repo, BLOCKED, PATCH_ID); }
            catch (AppException ex) { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            thread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            thread.interrupt(); thread.join(5000);
            assertFalse(thread.isAlive()); assertTrue(interrupted.get());
            assertTrue(observed.get().size() >= 2, "Both Git stages (and Windows dispatch children) must be observed"); assertStopped(observed.get());
        } finally { runner.abortActive(); thread.interrupt(); }
    }

    static List<ProcessHandle> gitChildren() {
        return ProcessHandle.current().descendants()
                .filter(process -> process.info().command().orElse("").toLowerCase(Locale.ROOT).endsWith("git.exe")
                        || process.info().command().orElse("").endsWith("/git")).toList();
    }
    static void assertStopped(List<ProcessHandle> processes) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (processes.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(10);
        });
    }
}


