package br.com.el.branchcontrol;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.List;

@RestController
@RequestMapping("/api")
public class EnvironmentController {
    private final EnvironmentStore environments;
    public EnvironmentController(EnvironmentStore environments) { this.environments=environments; }
    @GetMapping({"/environments","/admin/environments"}) public List<EnvironmentStore.Environment> list(Authentication auth) { return environments.list(AuthController.actor(auth)); }
    @PostMapping("/admin/environments") public EnvironmentStore.Environment create(@RequestBody EnvironmentStore.Change body,Authentication auth) { return environments.save(null,body,AuthController.actor(auth)); }
    @PutMapping("/admin/environments/{id}") public EnvironmentStore.Environment update(@PathVariable String id,@RequestBody EnvironmentStore.Change body,Authentication auth) { return environments.save(id,body,AuthController.actor(auth)); }
}
