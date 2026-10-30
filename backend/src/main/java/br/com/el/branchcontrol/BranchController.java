package br.com.el.branchcontrol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class BranchController {
    private final GitService git;
    private final RunService runs;
    private final PlanStore plans;
    private final EnvironmentStore environments;
    public BranchController(GitService git,RunService runs,PlanStore plans,EnvironmentStore environments) {
        this.git=git;this.runs=runs;this.plans=plans;this.environments=environments;
    }
    public record RepositoryRequest(@NotBlank String environmentId) {}
    public record AdminRepositoryRequest(@NotBlank String repositoryPath) {}
    public record SwitchRequest(@NotBlank String environmentId,@NotBlank String targetBranch) {}
    public record PlanRequest(@NotBlank String environmentId,@NotBlank String sourceBranch,@NotBlank String targetBranch,@NotNull LocalDate cutoff) {}
    public record ImportRequest(@NotBlank String environmentId,@NotBlank String targetBranch,@NotBlank @Size(max=2_000_000) String content) {}
    public record SelectionRequest(@NotEmpty @Size(max=500) List<String> selectedHashes,boolean simulation) {}
    @GetMapping("/config") public Map<String,Object> config() {
        return Map.of("workspaceRoot",git.workspaceRoot(),"defaultRepository","","javaVersion",System.getProperty("java.version"));
    }
    @GetMapping("/admin/repositories") public List<String> repositories() {
        try(var paths=Files.list(Path.of(git.workspaceRoot()))) {
            return paths.filter(Files::isDirectory).filter(path->Files.exists(path.resolve(".git"))).map(Path::toString).sorted().limit(200).toList();
        }catch(IOException ex){throw new AppException(500,"Não foi possível listar o workspace.");}
    }
    @PostMapping("/admin/repositories/open") public Models.RepositoryView adminOpen(@Valid @RequestBody AdminRepositoryRequest request) {return git.open(request.repositoryPath());}
    @PostMapping("/repositories/open") public Models.RepositoryView open(@Valid @RequestBody RepositoryRequest request,Authentication auth) {
        return environments.open(AuthController.actor(auth),request.environmentId());
    }
    @PostMapping("/repositories/switch") public Models.RepositoryView switchBranch(@Valid @RequestBody SwitchRequest request,Authentication auth) {
        var actor=AuthController.actor(auth);var env=environments.require(actor,request.environmentId(),Permissions.SWITCH);
        environments.branch(actor,env,request.targetBranch(),true);
        git.switchBranch(env.repositoryPath(),request.targetBranch());return environments.open(actor,env.id());
    }
    @PostMapping("/repositories/fetch") public Models.RepositoryView fetch(@Valid @RequestBody RepositoryRequest request,Authentication auth) {
        var actor=AuthController.actor(auth);var env=environments.require(actor,request.environmentId(),Permissions.FETCH);
        git.fetch(env.repositoryPath());return environments.open(actor,env.id());
    }
    @PostMapping("/plans") public Models.Plan plan(@Valid @RequestBody PlanRequest request,Authentication auth) {
        var actor=AuthController.actor(auth);var env=environments.require(actor,request.environmentId(),Permissions.PREPARE);
        environments.branch(actor,env,request.sourceBranch(),false);environments.branch(actor,env,request.targetBranch(),true);
        return plans.save(git.prepare(env.repositoryPath(),request.sourceBranch(),request.targetBranch(),request.cutoff()).inEnvironment(env.id(),env.revision()),actor);
    }
    @PostMapping("/plans/import") public Models.Plan importPlan(@Valid @RequestBody ImportRequest request,Authentication auth) {
        var actor=AuthController.actor(auth);var env=environments.require(actor,request.environmentId(),Permissions.PREPARE);
        environments.branch(actor,env,request.targetBranch(),true);
        var plan=git.importText(env.repositoryPath(),request.targetBranch(),request.content());
        environments.branch(actor,env,plan.sourceBranch(),false);
        return plans.save(plan.inEnvironment(env.id(),env.revision()),actor);
    }
    @PostMapping(value="/plans/{id}/export",produces="text/plain;charset=UTF-8")
    public ResponseEntity<String> export(@PathVariable String id,@Valid @RequestBody SelectionRequest request,Authentication auth) {
        var actor=AuthController.actor(auth);var plan=plans.get(id,actor);environments.validatePlan(actor,plan,Permissions.PREPARE);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=commits-cherry-pick.txt").body(git.exportText(plan,request.selectedHashes()));
    }
    @PostMapping("/plans/{id}/runs") public ResponseEntity<Models.RunView> execute(@PathVariable String id,@Valid @RequestBody SelectionRequest request,Authentication auth) {
        var actor=AuthController.actor(auth);
        return ResponseEntity.accepted().body(runs.start(plans.get(id,actor),request.selectedHashes(),request.simulation(),actor));
    }
    @GetMapping("/runs") public List<Models.RunView> history(Authentication auth){return runs.list(AuthController.actor(auth));}
    @GetMapping("/runs/{id}") public Models.RunView run(@PathVariable String id,Authentication auth){return runs.get(id,AuthController.actor(auth));}
    @GetMapping(value="/runs/{id}/log",produces="text/plain;charset=UTF-8")
    public ResponseEntity<String> log(@PathVariable String id,Authentication auth) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=branch-control-"+id+".log").body(runs.log(id,AuthController.actor(auth)));
    }
}
