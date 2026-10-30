package br.com.el.branchcontrol;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
final class LeaseGuardFilter extends OncePerRequestFilter {
    private final DatabaseInstanceLease lease;
    LeaseGuardFilter(DatabaseInstanceLease lease){this.lease=lease;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(request.getRequestURI().startsWith("/api/"))try{lease.verify();}catch(AppException ex){
            response.setStatus(503);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"message\":\"A conexão de exclusividade do banco foi perdida. Reinicie a aplicação e confira git status.\"}");return;
        }
        chain.doFilter(request,response);
    }
}
