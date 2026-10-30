package br.com.el.branchcontrol;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private final UsersStore users;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final LoginThrottle throttle;
    private final NetworkPolicy network;

    public AuthController(UsersStore users, AuthenticationManager authenticationManager, SecurityContextRepository contexts,
                          SessionAuthenticationStrategy sessions, LoginThrottle throttle, NetworkPolicy network) {
        this.users = users; this.authenticationManager = authenticationManager; this.contexts = contexts;
        this.sessions = sessions; this.throttle = throttle; this.network = network;
    }
    public record LoginRequest(@NotBlank @Size(max = 80) String username, @NotNull @Size(max = 200) String password) {}
    public record SetupRequest(@NotBlank @Size(max = 40) String username, @NotBlank @Size(max = 80) String name,
                               @NotNull @Size(max = 200) String password) {}

    @GetMapping("/session")
    public UserModels.SessionView session(Authentication authentication, CsrfToken token, HttpServletRequest request) {
        LocalPrincipal principal = principal(authentication);
        boolean setup = users.needsSetup();
        return new UserModels.SessionView(principal != null, principal == null ? null : principal.user(),
                token.getToken(), setup, setup && network.loopback(request.getRemoteAddr()));
    }

    @PostMapping("/setup")
    public UserModels.UserView setup(@Valid @RequestBody SetupRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (!network.loopback(request.getRemoteAddr())) throw new AppException(403, "Crie o primeiro administrador no computador servidor, acessando http://127.0.0.1:8087.");
        users.setupAdmin(body.username(), body.name(), body.password());
        LOG.info("Configuração inicial: primeiro administrador criado.");
        return login(new LoginRequest(body.username(), body.password()), request, response);
    }

    @PostMapping("/login")
    public UserModels.UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String peer = request.getRemoteAddr();
        throttle.check(peer);
        try {
            if (body.password().isEmpty() || body.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new BadCredentialsException("Credenciais inválidas.");
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            sessions.onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            LocalPrincipal principal = principal(authentication);
            if (principal == null) throw new BadCredentialsException("Credenciais inválidas.");
            LOG.info("Login: usuário={}, grupo={}, origem={}", principal.getUsername(), principal.user().group(), peer);
            return principal.user();
        } catch (AuthenticationException | IllegalArgumentException ex) {
            throttle.failed(peer);
            throw new AppException(401, "Login ou senha inválidos.");
        }
    }

    public static LocalPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof LocalPrincipal user ? user : null;
    }
    public static Models.Actor actor(Authentication authentication) {
        LocalPrincipal principal = principal(authentication);
        if (principal == null) throw new AppException(401, "Entre para continuar.");
        return principal.actor();
    }
}
