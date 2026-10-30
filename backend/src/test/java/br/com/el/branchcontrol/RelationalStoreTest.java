package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static br.com.el.branchcontrol.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class RelationalStoreTest {
    @Test void storesCandidatesProgressEnvironmentAndLogs() {
        var db = new TestDatabase(); var jdbc = new JdbcTemplate(db.source);
        String env = UUID.randomUUID().toString(), id = UUID.randomUUID().toString(), planId = UUID.randomUUID().toString();
        String now = "2026-10-30T11:00:00Z";
        var candidates = List.of(new Commit("a".repeat(40),now,"primeiro"), new Commit("b".repeat(40),now,"não selecionado"),new Commit("c".repeat(40),now,"terceiro"));
        var plan = new Plan(planId,"C:/repo","refs/heads/origem","destino","d".repeat(40),"e".repeat(40),"2026-10-29","America/Sao_Paulo",now,candidates,env,7);
        var selected = List.of(new CommitProgress(candidates.get(0).hash(),"primeiro","APPLIED","f".repeat(40)),new CommitProgress(candidates.get(2).hash(),"terceiro","CONFLICT",""));
        var run = new RunView(id,"CONFLICT",false,plan.repositoryPath(),plan.sourceBranch(),plan.targetBranch(),now,now,"histórico.log",selected,List.of(),new Actor("internal","interno","ADM"),planId,env,"Ambiente antigo");
        jdbc.update("INSERT INTO bc_environment(id,name,repository_path,enabled,revision) VALUES(CAST(? AS uuid),'Ambiente','C:/repo',true,7)",env);
        jdbc.update("INSERT INTO bc_environment_branch_pattern VALUES(CAST(? AS uuid),0,'draft/*'),(CAST(? AS uuid),1,'chore/*')",env,env);
        for (String permission : List.of("PREPARE","SIMULATE","APPLY","REPORTS")) jdbc.update("INSERT INTO bc_environment_permission VALUES(CAST(? AS uuid),?)",env,permission);
        String member = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO bc_user(id,username,name,password_hash,user_group,enabled,revision,created_at,updated_at) VALUES(CAST(? AS uuid),'usuario','Usuário',?,'USER',true,3,CAST(? AS timestamptz),CAST(? AS timestamptz))",member,"$2a$04$"+"a".repeat(53),now,now);
        jdbc.update("INSERT INTO bc_environment_member(environment_id,user_id) VALUES(CAST(? AS uuid),CAST(? AS uuid))",env,member);
        var store = new ExecutionStore(db.source);
        store.save(new RunService.StoredExecution(plan,run));
        store.appendLog(id,"linha um"); store.appendLog(id,"linha dois");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND data_type IN ('json','jsonb')",Integer.class,db.schema));
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)::text",String.class,db.schema+".bc_legacy_import"));
        assertNull(jdbc.queryForObject("SELECT to_regclass(?)::text",String.class,db.schema+".flyway_schema_history"));
        var restored = store.load().get(0);
        assertEquals(plan,restored.plan()); assertEquals(run,restored.run());
        assertEquals(List.of("linha um","linha dois"),store.tail(id));
        var environments = new EnvironmentStore(db.source,null); var environment = environments.find(env);
        assertEquals(List.of(member),environment.userIds());
        assertEquals(List.of("draft/*","chore/*"),environment.branchPatterns()); assertEquals(7,environment.revision());
        assertEquals(Set.of("PREPARE","SIMULATE","APPLY","REPORTS"),Set.copyOf(environment.permissions()));
        var completed = new RunView(id,"COMPLETED",false,run.repositoryPath(),run.sourceBranch(),run.targetBranch(),now,now,run.logPath(),List.of(selected.get(0),new CommitProgress(candidates.get(2).hash(),"terceiro","APPLIED","9".repeat(40))),List.of(),run.actor(),planId,env,run.environmentName());
        var candidateVersions = jdbc.queryForList("SELECT xmin::text FROM bc_execution_candidate ORDER BY position",String.class);
        store.save(new RunService.StoredExecution(plan,completed)); store.appendLog(id,"linha três");
        assertEquals(candidateVersions,jdbc.queryForList("SELECT xmin::text FROM bc_execution_candidate ORDER BY position",String.class));
        assertEquals(plan,store.load().get(0).plan()); assertEquals(completed,store.load().get(0).run());
        assertEquals(List.of("linha um","linha dois","linha três"),store.tail(id));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO bc_environment_permission(environment_id,permission) VALUES(CAST(? AS uuid),'UNKNOWN')",env));
        jdbc.update("DELETE FROM bc_environment_member WHERE environment_id=CAST(? AS uuid)",env);
        jdbc.update("DELETE FROM bc_environment_permission WHERE environment_id=CAST(? AS uuid)",env);
        jdbc.update("DELETE FROM bc_environment_branch_pattern WHERE environment_id=CAST(? AS uuid)",env);
        jdbc.update("DELETE FROM bc_environment WHERE id=CAST(? AS uuid)",env);
        assertEquals(completed,store.load().get(0).run());
    }
    @Test void reapplyingInitializationFailsWithoutErasingData() {
        var db = new TestDatabase(); var jdbc = new JdbcTemplate(db.source);
        jdbc.update("INSERT INTO bc_lock(name) VALUES ('preserved')");
        assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, db::initialize);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bc_lock WHERE name='preserved'",Integer.class));
    }
    @Test void insertsAtomicallyAndKeepsEmptyOrNullHistoricalEnvironmentIds() {
        var db = new TestDatabase(); var store = new ExecutionStore(db.source); var jdbc = new JdbcTemplate(db.source);
        for (String environment : Arrays.asList(null,"")) {
            String id=UUID.randomUUID().toString(), planId=UUID.randomUUID().toString(), now="2026-10-30T11:00:00.123456789Z";
            var plan=new Plan(planId,"C:/repo","origem","destino","a","b","2026-10-29","UTC",now,List.of(new Commit("a",now,"candidate")),environment,0);
            var run=new RunView(id,"RUNNING",true,"C:/repo","origem","destino",now,"","run.log",List.of(new CommitProgress("a","candidate","PENDING","")),List.of(),new Actor("internal","interno","ADM"),planId,environment,null);
            store.save(new RunService.StoredExecution(plan,run));
            var restored=store.load().stream().filter(x->x.run().id().equals(id)).findFirst().orElseThrow();
            assertEquals(plan,restored.plan()); assertEquals(run,restored.run());
        }
        String id=UUID.randomUUID().toString(), planId=UUID.randomUUID().toString(), now="2026-10-30T11:00:00Z";
        var plan=new Plan(planId,"repo","s","t","a","b","2026-10-29","UTC",now,List.of(new Commit("a",now,"candidate")));
        var invalid=new RunView(id,"RUNNING",true,"repo","s","t",now,"","run.log",List.of(new CommitProgress("a","candidate","PENDING",null)),List.of(),new Actor("internal","interno","ADM"),planId);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.save(new RunService.StoredExecution(plan,invalid)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bc_execution WHERE id=CAST(? AS uuid)",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bc_execution_candidate WHERE execution_id=CAST(? AS uuid)",Integer.class,id));
    }
}
