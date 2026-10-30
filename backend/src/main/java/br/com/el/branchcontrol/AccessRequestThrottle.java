package br.com.el.branchcontrol;

import org.springframework.stereotype.Component;
import java.time.Instant;
import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

@Component
public final class AccessRequestThrottle implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                if("POST".equals(request.getMethod())) attempt(request.getRemoteAddr());
                return true;
            }
        }).addPathPatterns("/api/auth/access-requests");
    }
    private record Window(Instant expires,int attempts) {}
    private final Map<String,Window> windows=new HashMap<>();
    public synchronized void attempt(String peer) {
        Instant now=Instant.now(); windows.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        Window window=windows.get(peer);
        if(window==null && windows.size()>=10000) throw new AppException(429,"Limite temporário de solicitações. Tente mais tarde.");
        if(window!=null && window.attempts()>=6) throw new AppException(429,"Muitas solicitações. Aguarde 10 minutos.");
        windows.put(peer,window==null?new Window(now.plusSeconds(600),1):new Window(window.expires(),window.attempts()+1));
    }
}
