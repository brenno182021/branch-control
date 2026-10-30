package br.com.el.branchcontrol;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class UsersController {
    private static final Logger LOG = LoggerFactory.getLogger(UsersController.class);
    private final UsersStore users;
    private final NetworkPolicy network;
    public UsersController(UsersStore users, NetworkPolicy network) { this.users = users; this.network = network; }

    public record CreateRequest(@NotBlank @Size(max=40) String username, @NotBlank @Size(max=80) String name,
                                @NotNull @Size(max=200) String password, @NotBlank String group) {}
    public record UpdateRequest(@NotBlank @Size(max=80) String name, @NotBlank String group, boolean enabled,
                                @Size(max=200) String password) {}

    @GetMapping("/users") public List<UserModels.UserView> list() { return users.list(); }
    @GetMapping("/network") public Map<String, Object> network() { return Map.of("urls", network.urls(), "groups", Groups.ALL); }

    @PostMapping("/users") public UserModels.UserView create(@Valid @RequestBody CreateRequest body, Authentication authentication) {
        var created = users.create(body.username(), body.name(), body.password(), body.group());
        LOG.info("Conta criada: usuário={}, grupo={}, responsável={}", created.username(), created.group(), AuthController.actor(authentication).username());
        return created;
    }

    @PutMapping("/users/{id}") public UserModels.UserView update(@PathVariable String id, @Valid @RequestBody UpdateRequest body,
                                                               Authentication authentication) {
        var updated = users.update(id, body.name(), body.group(), body.enabled(), body.password());
        LOG.info("Conta atualizada: usuário={}, grupo={}, ativa={}, responsável={}", updated.username(), updated.group(),
                updated.enabled(), AuthController.actor(authentication).username());
        return updated;
    }
}
