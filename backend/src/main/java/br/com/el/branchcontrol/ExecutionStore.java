package br.com.el.branchcontrol;

import org.springframework.stereotype.Repository;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static br.com.el.branchcontrol.Models.*;

@Repository
@DependsOnDatabaseInitialization
public class ExecutionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions, reads;
    public ExecutionStore(DataSource source) {
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public List<RunService.StoredExecution> load() {
        return reads.execute(status -> jdbc.query("SELECT * FROM bc_execution ORDER BY started_at", (rs,n) -> {
            String id = rs.getString("id");
            var candidates = jdbc.query("SELECT hash,commit_date,message FROM bc_execution_candidate WHERE execution_id=CAST(? AS uuid) ORDER BY position",
                (c,i) -> new Commit(c.getString(1),c.getString(2),c.getString(3)),id);
            var selected = jdbc.query("SELECT hash,message,state,new_hash FROM bc_execution_selected WHERE execution_id=CAST(? AS uuid) ORDER BY position",
                (c,i) -> new CommitProgress(c.getString(1),c.getString(2),c.getString(3),c.getString(4)),id);
            var plan = new Plan(rs.getString("plan_id"),rs.getString("repository_path"),rs.getString("source_branch"),rs.getString("target_branch"),
                rs.getString("source_head"),rs.getString("target_head"),rs.getString("cutoff"),rs.getString("zone_id"),rs.getString("plan_created_at"),
                candidates,rs.getString("environment_id"),rs.getLong("environment_revision"));
            var run = new RunView(id,rs.getString("state"),rs.getBoolean("simulation"),rs.getString("run_repository_path"),rs.getString("run_source_branch"),
                rs.getString("run_target_branch"),rs.getString("run_started_at"),rs.getString("run_finished_at"),rs.getString("log_path"),selected,List.of(),
                new Actor(rs.getString("actor_id"),rs.getString("actor_username"),rs.getString("actor_group")),rs.getString("plan_id"),
                rs.getString("run_environment_id"),rs.getString("environment_name"));
            var stored = new RunService.StoredExecution(plan,run); validate(stored); return stored;
        }));
    }
    public void save(RunService.StoredExecution stored) {
        validate(stored); var run = stored.run(); var plan = stored.plan();
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.update("""
                INSERT INTO bc_execution(id,actor_id,actor_username,actor_group,plan_id,state,simulation,started_at,finished_at,
                    repository_path,source_branch,target_branch,source_head,target_head,cutoff,zone_id,plan_created_at,environment_id,environment_revision,
                    run_repository_path,run_source_branch,run_target_branch,run_started_at,run_finished_at,log_path,run_environment_id,environment_name)
                VALUES(CAST(? AS uuid),?,?,?,CAST(? AS uuid),?,?,CAST(? AS timestamptz),CAST(? AS timestamptz),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO NOTHING
                """,run.id(),run.actor().userId(),run.actor().username(),run.actor().group(),run.planId(),run.state(),run.simulation(),run.startedAt(),
                run.finishedAt().isEmpty()?null:run.finishedAt(),plan.repositoryPath(),plan.sourceBranch(),plan.targetBranch(),plan.sourceHead(),plan.targetHead(),
                plan.cutoff(),plan.zoneId(),plan.createdAt(),plan.environmentId(),plan.environmentRevision(),run.repositoryPath(),run.sourceBranch(),run.targetBranch(),
                run.startedAt(),run.finishedAt(),run.logPath(),run.environmentId(),run.environmentName());
            if (inserted == 1) {
                for(int i=0;i<plan.commits().size();i++) {
                    var commit=plan.commits().get(i);
                    jdbc.update("INSERT INTO bc_execution_candidate(execution_id,position,hash,commit_date,message) VALUES(CAST(? AS uuid),?,?,?,?)",
                        run.id(),i,commit.hash(),commit.date(),commit.message());
                }
                for(int i=0;i<run.commits().size();i++) {
                    var commit=run.commits().get(i);
                    jdbc.update("INSERT INTO bc_execution_selected(execution_id,position,hash,message,state,new_hash) VALUES(CAST(? AS uuid),?,?,?,?,?)",
                        run.id(),i,commit.hash(),commit.message(),commit.state(),commit.newHash());
                }
            } else {
                jdbc.update("UPDATE bc_execution SET state=?,finished_at=CAST(? AS timestamptz),run_finished_at=?,updated_at=current_timestamp WHERE id=CAST(? AS uuid)",
                    run.state(),run.finishedAt().isEmpty()?null:run.finishedAt(),run.finishedAt(),run.id());
                for(int i=0;i<run.commits().size();i++) {
                    var commit=run.commits().get(i);
                    jdbc.update("""
                        UPDATE bc_execution_selected SET state=?,new_hash=? WHERE execution_id=CAST(? AS uuid) AND position=? AND hash=?
                            AND (state IS DISTINCT FROM ? OR new_hash IS DISTINCT FROM ?)
                        """,commit.state(),commit.newHash(),run.id(),i,commit.hash(),commit.state(),commit.newHash());
                }
            }
        });
    }
    public void appendLog(String id, String line) {
        jdbc.update("INSERT INTO bc_execution_log(execution_id,line) VALUES(CAST(? AS uuid),?)", id, line);
    }
    public List<String> tail(String id) {
        var lines = new ArrayList<>(jdbc.queryForList("SELECT line FROM bc_execution_log WHERE execution_id=CAST(? AS uuid) ORDER BY sequence DESC LIMIT 2000", String.class, id));
        Collections.reverse(lines); return List.copyOf(lines);
    }
    public String log(String id) {
        return String.join(System.lineSeparator(), jdbc.queryForList("SELECT line FROM bc_execution_log WHERE execution_id=CAST(? AS uuid) ORDER BY sequence", String.class, id));
    }
    static void validate(RunService.StoredExecution stored) {
        if (stored == null || stored.run() == null || stored.plan() == null) throw new IllegalStateException("Histórico inválido.");
        var view = stored.run(); UUID.fromString(view.id()); UUID.fromString(view.planId());
        if (!stored.plan().id().equals(view.planId()) || view.actor() == null || view.actor().userId() == null || view.actor().username() == null || view.actor().group() == null || view.actor().group().isBlank() || view.commits() == null || stored.plan().commits() == null || view.finishedAt() == null) throw new IllegalStateException("Histórico inválido.");
        Instant.parse(view.startedAt()); if (!view.finishedAt().isBlank()) Instant.parse(view.finishedAt());
    }
}
