package br.com.el.branchcontrol;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
public class LoginThrottle {
    private record Window(Instant expires, int failures) {}
    private final Map<String, Window> windows = new HashMap<>();
    public synchronized void check(String peer) {
        cleanup();
        Window window = windows.get(peer);
        if (window != null && window.failures() >= 6) throw new AppException(429, "Muitas tentativas inválidas. Aguarde 10 minutos antes de tentar novamente.");
    }
    public synchronized void failed(String peer) {
        cleanup();
        Window window = windows.get(peer);
        windows.put(peer, window == null ? new Window(Instant.now().plusSeconds(600), 1) :
                new Window(window.expires(), window.failures() + 1));
    }
    private void cleanup() {
        windows.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        if (windows.size() > 10_000) throw new AppException(429, "Limite temporário de tentativas. Tente mais tarde.");
    }
}
