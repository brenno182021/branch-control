package br.com.el.branchcontrol;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public final class AccessRequestsController {
    public record Submit(String username,String name,String password) {}
    public record Approve(String environmentId) {}
    public record Reject(String reason) {}
    private final AccessRequestsStore store;
    public AccessRequestsController(AccessRequestsStore store) { this.store=store; }
    @PostMapping("/api/auth/access-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public AccessRequestsStore.Submitted submit(@RequestBody Submit body) {
        return store.submit(body.username(),body.name(),body.password());
    }
    @GetMapping("/api/access-requests")
    public List<AccessRequestsStore.View> pending(Authentication auth) { return store.pending(AuthController.actor(auth)); }
    @PostMapping("/api/access-requests/{id}/approve")
    public AccessRequestsStore.View approve(Authentication auth,@PathVariable String id,@RequestBody Approve body) { return store.approve(AuthController.actor(auth),id,body.environmentId()); }
    @PostMapping("/api/access-requests/{id}/reject")
    public AccessRequestsStore.View reject(Authentication auth,@PathVariable String id,@RequestBody(required=false) Reject body) { return store.reject(AuthController.actor(auth),id,body==null?null:body.reason()); }
}
