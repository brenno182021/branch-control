package br.com.el.branchcontrol;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import static br.com.el.branchcontrol.Models.*;

@RestController
@RequestMapping("/api")
public class ReportController {
    private final RunService runs;
    private final ZoneId zone;
    private final EnvironmentStore environments;
    public ReportController(RunService runs, AppProperties properties, EnvironmentStore environments) { this.runs = runs; this.environments=environments; zone = ZoneId.of(properties.zoneId()); }
    public record Report(List<RunView> runs, int total, long applied, long simulations, long failed) {}
    private List<RunView> filter(Authentication auth, LocalDate from, LocalDate to, String state, String environmentId) {
        var actor=AuthController.actor(auth);
        var environment=environmentId==null||environmentId.isBlank()?null:environments.require(actor,environmentId,Permissions.REPORTS);
        if (from != null && to != null && from.isAfter(to)) throw AppException.bad("A data inicial deve ser anterior à final.");
        if (state != null && !List.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CONFLICT", "INTERRUPTED").contains(state)) throw AppException.bad("Resultado inválido.");
        Instant start = from == null ? Instant.MIN : from.atStartOfDay(zone).toInstant();
        Instant end = to == null ? Instant.MAX : to.plusDays(1).atStartOfDay(zone).toInstant();
        return runs.reports(actor).stream().filter(run -> environment==null || (environment.repositoryPath().equals(run.repositoryPath()) && (environments.administrator(actor)||(EnvironmentStore.allows(environment,run.sourceBranch())&&EnvironmentStore.allows(environment,run.targetBranch()))))).filter(run -> {
            Instant date = Instant.parse(run.startedAt());
            return !date.isBefore(start) && date.isBefore(end) && (state == null || state.equals(run.state()));
        }).toList();
    }
    @GetMapping("/reports") public Report report(Authentication auth,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String state, @RequestParam(required=false) String environmentId) {
        List<RunView> result = filter(auth, from, to, state,environmentId);
        return new Report(result, result.size(), result.stream().filter(run -> !run.simulation()).flatMap(run -> run.commits().stream()).filter(commit -> commit.state().equals("APPLIED")).count(),
                result.stream().filter(RunView::simulation).count(), result.stream().filter(run -> List.of("FAILED", "CONFLICT", "INTERRUPTED").contains(run.state())).count());
    }
    @GetMapping(value = "/reports.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> csv(Authentication auth, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, @RequestParam(required = false) String state, @RequestParam(required=false) String environmentId) {
        StringBuilder csv = new StringBuilder("\uFEFFdata;usuario;grupo;tipo;resultado;origem;destino;commit;hash_original;hash_aplicado;resultado_commit\r\n");
        for (RunView run : filter(auth, from, to, state,environmentId)) for (CommitProgress commit : run.commits()) {
            csv.append(String.join(";", List.of(run.startedAt(), run.actor().username(), run.actor().group(), run.simulation() ? "Simulação" : "Cherry-pick", run.state(), run.sourceBranch(), run.targetBranch(), commit.message(), commit.hash(), commit.newHash(), commit.state()).stream().map(ReportController::cell).toList())).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=branch-control-relatorio.csv").body(csv.toString());
    }
    static String cell(String value) {
        String clean = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        if (clean.stripLeading().matches("^[=+@-].*")) clean = "'" + clean;
        return "\"" + clean.replace("\"", "\"\"") + "\"";
    }
}
