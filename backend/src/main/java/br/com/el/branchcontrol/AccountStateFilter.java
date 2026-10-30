package br.com.el.branchcontrol;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

final class AccountStateFilter extends OncePerRequestFilter {
    private final UsersStore users;
    AccountStateFilter(UsersStore users) { this.users = users; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LocalPrincipal principal) {
            var current = users.findById(principal.user().id());
            if (current.isEmpty() || !current.get().enabled() || current.get().revision() != principal.revision()) {
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sua conta foi atualizada. Entre novamente.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
