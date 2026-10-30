package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LocalApiFilterTest {
    private final LocalApiFilter filter = new LocalApiFilter(8087);

    @Test void allowsPortSharedWithDevelopmentDefault() {
        assertDoesNotThrow(() -> new LocalApiFilter(5173));
    }

    @Test void rejectsMissingHeaderAndExternalOrigin() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/repositories/switch");
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertEquals(403, response.getStatus());
        assertFalse(reached.get());

        request = new MockHttpServletRequest("POST", "/api/repositories/switch");
        request.addHeader("X-Branch-Control", "local");
        request.addHeader("Origin", "https://external.example");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertEquals(403, response.getStatus());
        assertFalse(reached.get());
    }

    @Test void allowsIdentifiedLocalClientAndDevelopmentPreflight() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/plans");
        request.addHeader("X-Branch-Control", "local");
        request.addHeader("Origin", "http://127.0.0.1:8087");
        request.setServerName("127.0.0.1");
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        assertTrue(reached.get());

        request = new MockHttpServletRequest("OPTIONS", "/api/plans");
        request.addHeader("Origin", "http://127.0.0.1:5173");
        request.setServerName("127.0.0.1");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Preflight não deve executar o endpoint."));
        assertEquals(204, response.getStatus());
        assertEquals("http://127.0.0.1:5173", response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains("X-Branch-Control"));
    }
}
