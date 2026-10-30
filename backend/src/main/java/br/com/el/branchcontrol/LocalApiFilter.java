package br.com.el.branchcontrol;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalApiFilter extends OncePerRequestFilter {
    private final NetworkPolicy network;

    public LocalApiFilter(int port) { this(new NetworkPolicy(port)); }
    @Autowired public LocalApiFilter(NetworkPolicy network) { this.network = network; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!network.allowedPeer(request.getRemoteAddr()) || !network.allowedHost(request.getServerName())) { reject(response); return; }
        if (!request.getRequestURI().startsWith("/api/")) { chain.doFilter(request, response); return; }
        String origin = request.getHeader("Origin");
        if (origin != null && !network.allowedOrigin(origin, request.getServerName())) { reject(response); return; }
        if (request.getContentLengthLong() > 3_000_000) {
            response.setStatus(413);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"Requisição maior que 3 MB.\"}");
            return;
        }
        if ("OPTIONS".equals(request.getMethod())) {
            if (origin != null) response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type,X-Branch-Control,X-CSRF-TOKEN");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Vary", "Origin");
            response.setStatus(204);
            return;
        }
        if (!"local".equals(request.getHeader("X-Branch-Control"))) { reject(response); return; }
        if (origin != null) { response.setHeader("Access-Control-Allow-Origin", origin); response.setHeader("Access-Control-Allow-Credentials", "true"); response.setHeader("Vary", "Origin"); }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"Origem ou identificação local não permitida.\"}");
    }
}
