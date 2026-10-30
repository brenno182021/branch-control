package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static br.com.el.branchcontrol.Models.*;
import static br.com.el.branchcontrol.UserModels.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyImportTest {
    @TempDir Path root;
    @Test void importsOriginalHashAndLogsOnceWithoutChangingSourceFiles() throws Exception {
        var db=new TestDatabase(); var encoder=new BCryptPasswordEncoder(4);
        String id=UUID.randomUUID().toString(), now=Instant.now().toString(), runId=UUID.randomUUID().toString(), planId=UUID.randomUUID().toString();
        String hash=encoder.encode("Senha-teste-123");
        var account=new Account(id,"legado","Legado",hash,"RHF-ADM",true,1,now,now);
        AtomicJsonFile.write(root.resolve("users.json"),new UsersFile(1,List.of(account)),false);
        var plan=new Plan(planId,root.toString(),"refs/heads/draft/rhf/time","chore/rhf/entrega","a".repeat(40),"b".repeat(40),"2026-10-30","America/Sao_Paulo",now,List.of());
        var run=new RunView(runId,"COMPLETED",true,root.toString(),plan.sourceBranch(),plan.targetBranch(),now,now,"ignored",List.of(),List.of(),account.actor(),planId);
        AtomicJsonFile.write(root.resolve("history").resolve(runId+".json"),new RunService.Snapshot(1,plan,run),false);
        Files.createDirectory(root.resolve("logs")); Files.writeString(root.resolve("logs").resolve(runId+".log"),"linha original\n");
        var users=new UsersStore(db.source,encoder);var executions=new ExecutionStore(db.source);
        assertThrows(IllegalStateException.class,()->LegacyImport.importFrom(db.source,users,executions,false,root));
        assertTrue(users.needsSetup());
        String original=Files.readString(root.resolve("users.json"));
        LegacyImport.importFrom(db.source,users,executions,true,root);
        LegacyImport.importFrom(db.source,users,executions,true,root);
        assertEquals(1,users.list().size()); assertEquals(Groups.ADM,users.list().get(0).group());
        assertEquals(hash,users.findById(id).orElseThrow().passwordHash());
        assertEquals(1,executions.load().size());assertEquals("linha original",executions.log(runId));
        assertEquals(original,Files.readString(root.resolve("users.json")));
        assertEquals(1,new JdbcTemplate(db.source).queryForObject("SELECT count(*) FROM bc_legacy_import",Integer.class));
    }
    @Test void invalidLegacyFileLeavesDatabaseEmptyAndLeaseRejectsSecondInstance() throws Exception {
        var db=new TestDatabase();var users=new UsersStore(db.source,new BCryptPasswordEncoder(4));var executions=new ExecutionStore(db.source);
        Files.writeString(root.resolve("users.json"),"{invalido");
        assertThrows(IllegalStateException.class,()->LegacyImport.importFrom(db.source,users,executions,true,root));
        assertTrue(users.needsSetup());assertTrue(executions.load().isEmpty());
        try(var lease=new Lease(new DatabaseInstanceLease(db.source))) {
            assertThrows(IllegalStateException.class,()->new DatabaseInstanceLease(db.source));
        }
        new DatabaseInstanceLease(db.source).close();
    }
    record Lease(DatabaseInstanceLease lease) implements AutoCloseable { public void close() throws Exception {lease.close();} }
    @Test void lostLeasePermanentlyBlocksOriginalInstanceEvenAfterAnotherAcquiresLock() throws Exception {
        var db=new TestDatabase();var jdbc=new JdbcTemplate(db.source);
        try(var first=new DatabaseInstanceLease(db.source)) {
            first.verify();
            assertTrue(jdbc.queryForObject("SELECT pg_terminate_backend(?)",Boolean.class,first.processId()));
            assertThrows(AppException.class,first::verify);
            try(var second=new DatabaseInstanceLease(db.source)) {
                second.verify();
                assertThrows(AppException.class,first::verify);
                var guardedGit=new GitRunner(30,first::verify);
                assertThrows(AppException.class,()->guardedGit.run(root,"--version"));
            }
        }
    }
    @Test void losingLeaseCancelsAnAlreadyStartedGitProcessImmediately() throws Exception {
        var db=new TestDatabase();var ready=new java.util.concurrent.CountDownLatch(1);
        var checks=new java.util.concurrent.atomic.AtomicInteger();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var lease=new DatabaseInstanceLease(db.source)) {
            var runner=new GitRunner(30,()->{lease.verify();if(checks.incrementAndGet()==2)ready.countDown();});
            lease.onLoss(runner::abortActive);
            var running=executor.submit(()->{try{return runner.run(root,"hash-object","--stdin").code()!=0;}catch(AppException ex){return true;}});
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));
            new JdbcTemplate(db.source).queryForObject("SELECT pg_terminate_backend(?)",Boolean.class,lease.processId());
            assertThrows(AppException.class,lease::verify);
            assertTrue(running.get(5,java.util.concurrent.TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
    }
}
