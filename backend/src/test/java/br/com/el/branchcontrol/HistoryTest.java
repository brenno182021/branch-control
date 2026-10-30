package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static br.com.el.branchcontrol.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryTest {
    @TempDir Path root;
    @Test void interruptedExecutionIsNotResumedAndHistoryFailsClosed() throws Exception {
        String id = UUID.randomUUID().toString(), planId = UUID.randomUUID().toString(), now = Instant.now().toString();
        var actor = new Actor("dev", "dev", Groups.USER);
        var plan = new Plan(planId, root.toString(), "refs/heads/origem", "destino", "a".repeat(40), "b".repeat(40), "2026-10-29", "America/Sao_Paulo", now, List.of());
        var run = new RunView(id, "RUNNING", false, root.toString(), plan.sourceBranch(), plan.targetBranch(), now, "", "untrusted-path.log", List.of(), List.of(), actor, planId);
        var db = new TestDatabase();
        var history = new ExecutionStore(db.source);
        history.save(new RunService.StoredExecution(plan, run));
        var properties = new AppProperties(root.toString(), root.resolve("logs").toString(), "America/Sao_Paulo", 30);
        var git = new GitService(new GitRunner(30), properties);
        try (var jobs = new RunService(git, properties, history)) {
            var restored = jobs.get(id, actor);
            assertEquals("INTERRUPTED", restored.state());
            assertFalse(restored.finishedAt().isBlank());
            assertEquals(root.resolve("logs").resolve(id + ".log").toString(), restored.logPath());
            assertTrue(jobs.log(id, actor).contains("nenhuma operação foi retomada"));
            assertEquals(0, jobs.list(new Actor("tester", "teste", Groups.USER)).size());
        }
        assertEquals("INTERRUPTED", history.load().get(0).run().state());
        new org.springframework.jdbc.core.JdbcTemplate(db.source).update("UPDATE bc_execution SET run_started_at='inválido'");
        assertThrows(IllegalStateException.class, () -> new RunService(git, properties, history));
    }
    @Test void csvNeutralizesFormulaPrefixesAndEscapesQuotes() {
        for (String formula : List.of("=cmd", "+cmd", "-cmd", "@cmd", "  =cmd", "\t=cmd")) {
            assertTrue(ReportController.cell(formula).startsWith("\"'"));
        }
        assertEquals("\"mensagem \"\"teste\"\"\"", ReportController.cell("mensagem \"teste\""));
    }
    @Test void invalidLoginsAreTemporarilyThrottled() {
        var throttle = new LoginThrottle();
        for (int i = 0; i < 6; i++) { assertDoesNotThrow(() -> throttle.check("127.0.0.1")); throttle.failed("127.0.0.1"); }
        assertThrows(AppException.class, () -> throttle.check("127.0.0.1"));
        assertDoesNotThrow(() -> throttle.check("192.168.1.5"));
    }
}
