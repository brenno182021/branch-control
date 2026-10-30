package br.com.el.branchcontrol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class DatabaseInstanceLeaseTest {
    @TempDir Path root;
    @Test void leaseRejectsSecondInstanceAndCanBeReacquiredAfterClose() throws Exception {
        var db = new TestDatabase();
        try(var first=new DatabaseInstanceLease(db.source)) {
            assertThrows(IllegalStateException.class,()->new DatabaseInstanceLease(db.source));
        }
        new DatabaseInstanceLease(db.source).close();
    }
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
