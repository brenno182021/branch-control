package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessRequestsStoreTest {
    final TestDatabase db=new TestDatabase();
    final JdbcTemplate jdbc=new JdbcTemplate(db.source);
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    final UsersStore users=new UsersStore(db.source,encoder);
    final AccessRequestsStore requests=new AccessRequestsStore(db.source,users,encoder);
    final String password="Spéçial-MiXeD!123";
    UserModels.Account admin() { users.setupAdmin("admin","Admin",password); return users.findByUsername("admin").orElseThrow(); }
    String environment(String name,boolean enabled,String member,boolean approve) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO bc_environment(id,name,repository_path,enabled,revision) VALUES(CAST(? AS uuid),?,'/test',?,1)",id,name,enabled);
        if(member!=null) jdbc.update("INSERT INTO bc_environment_member VALUES(CAST(? AS uuid),CAST(? AS uuid))",id,member);
        if(approve) jdbc.update("INSERT INTO bc_environment_permission VALUES(CAST(? AS uuid),'APPROVE_USERS')",id);
        return id;
    }
    void status(int code,Runnable task) { assertEquals(code,assertThrows(AppException.class,task::run).status()); }
    @Test void initialSetupRequiredAndNoEnvironmentIsMandatory() {
        status(403,()->requests.submit("new.user","New",password));
        var admin=admin(); var request=requests.submit("new.user","New",password);
        status(400,()->requests.approve(admin.actor(),request.id(),null));
        String disabled=environment("Disabled",false,null,false);
        status(403,()->requests.approve(admin.actor(),request.id(),disabled));
        assertTrue(users.findByUsername("new.user").isEmpty());
    }
    @Test void approvalCreatesEnabledUserMembershipAndAuditWithoutExposingSecrets() throws Exception {
        var admin=admin(); String env=environment("Enabled",true,null,false);
        var submitted=requests.submit(" Mixed.Name ","Nova Pessoa",password);
        var view=requests.approve(admin.actor(),submitted.id(),env);
        var account=users.findByUsername("MIXED.NAME").orElseThrow();
        assertEquals("USER",account.group()); assertTrue(account.enabled()); assertTrue(encoder.matches(password,account.passwordHash()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bc_environment_member WHERE environment_id=CAST(? AS uuid) AND user_id=CAST(? AS uuid)",Integer.class,env,account.id()));
        assertEquals(2,jdbc.queryForObject("SELECT revision FROM bc_environment WHERE id=CAST(? AS uuid)",Long.class,env));
        assertEquals("APPROVED",view.status()); assertEquals(env,view.environmentId()); assertEquals("Enabled",view.environmentName()); assertEquals("admin",view.reviewerUsername()); assertNotNull(view.reviewedAt());
        assertNull(jdbc.queryForObject("SELECT password_hash FROM bc_access_request WHERE id=CAST(? AS uuid)",String.class,view.id()));
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(view);
        assertFalse(json.contains("password")); assertFalse(json.contains("$2"));
        status(409,()->requests.approve(admin.actor(),submitted.id(),env));
        assertTrue(requests.pending(admin.actor()).isEmpty());
        jdbc.update("DELETE FROM bc_environment_member WHERE environment_id=CAST(? AS uuid)",env);
        jdbc.update("DELETE FROM bc_environment WHERE id=CAST(? AS uuid)",env);
        assertEquals("Enabled",jdbc.queryForObject("SELECT environment_name FROM bc_access_request WHERE id=CAST(? AS uuid)",String.class,view.id()));
    }
    @Test void currentPermissionMembershipEnvironmentAndAccountAreRechecked() {
        var admin=admin(); users.create("reviewer","Reviewer",password,"USER"); var reviewer=users.findByUsername("reviewer").orElseThrow();
        var req=requests.submit("candidate","Candidate",password);
        status(403,()->requests.pending(reviewer.actor())); status(403,()->requests.reject(reviewer.actor(),req.id(),null));
        String env=environment("Authorized",true,reviewer.id(),true), forged=environment("Forged",true,null,true);
        assertEquals(1,requests.pending(reviewer.actor()).size());
        status(403,()->requests.approve(reviewer.actor(),req.id(),forged));
        jdbc.update("DELETE FROM bc_environment_permission WHERE environment_id=CAST(? AS uuid)",env);
        status(403,()->requests.approve(reviewer.actor(),req.id(),env));
        jdbc.update("INSERT INTO bc_environment_permission VALUES(CAST(? AS uuid),'APPROVE_USERS')",env);
        jdbc.update("UPDATE bc_environment SET enabled=false WHERE id=CAST(? AS uuid)",env);
        status(403,()->requests.pending(reviewer.actor()));
        jdbc.update("UPDATE bc_environment SET enabled=true WHERE id=CAST(? AS uuid)",env);
        users.update(reviewer.id(),reviewer.name(),"USER",false,null);
        status(403,()->requests.reject(reviewer.actor(),req.id(),null));
        users.update(reviewer.id(),reviewer.name(),"USER",true,null);
        assertEquals("APPROVED",requests.approve(reviewer.actor(),req.id(),env).status());
    }
    @Test void rejectionClearsHashAllowsResubmissionAndExistingUsernameBlocksApproval() {
        var admin=admin(); String env=environment("Enabled",true,null,false);
        var req=requests.submit("candidate","Candidate",password);
        status(409,()->requests.submit("CANDIDATE","Other",password));
        status(400,()->requests.reject(admin.actor(),req.id(),"x".repeat(501)));
        var rejected=requests.reject(admin.actor(),req.id(),"  Sem vínculo  ");
        assertEquals("REJECTED",rejected.status()); assertEquals("Sem vínculo",rejected.rejectionReason());
        assertNull(jdbc.queryForObject("SELECT password_hash FROM bc_access_request WHERE id=CAST(? AS uuid)",String.class,req.id()));
        var again=requests.submit("CANDIDATE","Candidate",password);
        users.create("candidate","Existing",password,"USER");
        status(409,()->requests.approve(admin.actor(),again.id(),env));
        status(409,()->requests.submit("ADMIN","Existing",password));
    }
    @Test void concurrentApprovalsCreateOnlyOneAccountAndMembership() throws Exception {
        var admin=admin(); String env=environment("Enabled",true,null,false); var req=requests.submit("racing","Racing",password);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> approve=()-> { try { requests.approve(admin.actor(),req.id(),env); return 200; } catch(AppException e) { return e.status(); } };
            var results=pool.invokeAll(List.of(approve,approve));
            var codes=new ArrayList<>(List.of(results.get(0).get(),results.get(1).get())); Collections.sort(codes);
            assertEquals(List.of(200,409),codes);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bc_user WHERE username='racing'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bc_environment_member WHERE environment_id=CAST(? AS uuid)",Integer.class,env));
        } finally { pool.shutdownNow(); }
    }
    @Test void databaseFailureRollsBackAccountMembershipRevisionAndReview() {
        var admin=admin(); String env=environment("Enabled",true,null,false); var req=requests.submit("rollback","Rollback",password);
        jdbc.execute("ALTER TABLE bc_access_request ADD CONSTRAINT force_rollback CHECK(status<>'APPROVED')");
        assertThrows(org.springframework.dao.DataAccessException.class,()->requests.approve(admin.actor(),req.id(),env));
        assertTrue(users.findByUsername("rollback").isEmpty()); assertEquals(1,requests.pending(admin.actor()).size());
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM bc_environment WHERE id=CAST(? AS uuid)",Long.class,env));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bc_environment_member",Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT password_hash FROM bc_access_request WHERE id=CAST(? AS uuid)",String.class,req.id()));
    }
    @Test void invalidAttemptsAndSuccessfulSubmissionsConsumeIndependentThrottle() {
        AccessRequestThrottle throttle=new AccessRequestThrottle();
        for(int i=0;i<6;i++) throttle.attempt("127.0.0.1");
        status(429,()->throttle.attempt("127.0.0.1")); assertDoesNotThrow(()->throttle.attempt("127.0.0.2"));
        assertDoesNotThrow(()->new LoginThrottle().check("127.0.0.1"));
    }
    @Test void schemaRejectsPendingWithoutHashAndLimitsPendingQueue() {
        var admin=admin();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bc_access_request(id,username,name,status,created_at) VALUES(CAST(? AS uuid),'invalid','Invalid','PENDING',current_timestamp)",UUID.randomUUID().toString()));
        String hash=encoder.encode(password);
        jdbc.update("INSERT INTO bc_access_request(id,username,name,password_hash,status,created_at) SELECT gen_random_uuid(),'queue.'||n,'Queue',?,'PENDING',current_timestamp FROM generate_series(1,1000) n",hash);
        status(429,()->requests.submit("over.limit","Overflow",password));
        assertEquals(1000,requests.pending(admin.actor()).size());
    }
    @Test void approvalPermissionAloneDoesNotExposeGitRepository() {
        var admin=admin(); users.create("reviewer","Reviewer",password,"USER"); var reviewer=users.findByUsername("reviewer").orElseThrow();
        String env=environment("OnlyApproval",true,reviewer.id(),true);
        var properties=new AppProperties("./data/test-logs","America/Sao_Paulo",30);
        var environments=new EnvironmentStore(db.source,new GitService(new GitRunner(30),properties));
        status(403,()->environments.open(reviewer.actor(),env));
        jdbc.update("INSERT INTO bc_environment_branch_pattern VALUES(CAST(? AS uuid),0,'*')",env);
        var ownRun=new Models.RunView(UUID.randomUUID().toString(),"COMPLETED",false,"/test","source","target","","","",List.of(),List.of("private log"),reviewer.actor(),"plan",env,"OnlyApproval");
        assertFalse(environments.canRead(reviewer.actor(),ownRun));
        jdbc.update("INSERT INTO bc_environment_permission VALUES(CAST(? AS uuid),'REPORTS')",env);
        assertTrue(environments.canRead(reviewer.actor(),ownRun));
    }
}
