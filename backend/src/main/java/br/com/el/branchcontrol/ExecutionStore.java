package br.com.el.branchcontrol;

import org.springframework.stereotype.Repository;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

@Repository
@DependsOnDatabaseInitialization
public class ExecutionStore {
    private final JdbcTemplate jdbc;
    public ExecutionStore(DataSource source) { jdbc = new JdbcTemplate(source); }
    public List<RunService.Snapshot> load() {
        return jdbc.query("SELECT snapshot::text FROM bc_execution ORDER BY started_at", (rs,n) -> {
            var snapshot = JsonCodec.read(rs.getString(1), RunService.Snapshot.class); validate(snapshot); return snapshot;
        });
    }
    public void save(RunService.Snapshot snapshot) {
        validate(snapshot); var run = snapshot.run();
        jdbc.update("""
            INSERT INTO bc_execution(id,actor_id,actor_username,actor_group,plan_id,state,simulation,started_at,finished_at,snapshot)
            VALUES(CAST(? AS uuid),?,?,?,CAST(? AS uuid),?,?,CAST(? AS timestamptz),CAST(? AS timestamptz),CAST(? AS jsonb))
            ON CONFLICT(id) DO UPDATE SET state=excluded.state,finished_at=excluded.finished_at,snapshot=excluded.snapshot,updated_at=current_timestamp
            """, run.id(), run.actor().userId(), run.actor().username(), run.actor().group(), run.planId(), run.state(), run.simulation(),
                run.startedAt(), run.finishedAt().isEmpty() ? null : run.finishedAt(), JsonCodec.write(snapshot));
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
    static void validate(RunService.Snapshot snapshot) {
        if (snapshot == null || snapshot.version() != 1 || snapshot.run() == null || snapshot.plan() == null) throw new IllegalStateException("Histórico inválido.");
        var view = snapshot.run(); UUID.fromString(view.id()); UUID.fromString(view.planId());
        if (!snapshot.plan().id().equals(view.planId()) || view.actor() == null || view.actor().userId() == null || view.actor().username() == null || view.actor().group() == null || view.actor().group().isBlank() || view.commits() == null || view.finishedAt() == null) throw new IllegalStateException("Histórico inválido.");
        Instant.parse(view.startedAt()); if (!view.finishedAt().isBlank()) Instant.parse(view.finishedAt());
    }
}
