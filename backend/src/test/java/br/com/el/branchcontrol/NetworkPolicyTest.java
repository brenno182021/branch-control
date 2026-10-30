package br.com.el.branchcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkPolicyTest {
    @Test void allowsPrivatePeersButRejectsPublicAndUnresolvableAddresses() {
        var policy = new NetworkPolicy(8087);
        for (String peer : new String[]{"127.0.0.1", "::1", "10.0.0.2", "172.16.1.2", "192.168.10.20", "fd12::10", "100.64.4.51", "100.127.255.254"}) {
            assertTrue(policy.allowedPeer(peer), peer);
        }
        for (String peer : new String[]{"203.0.113.12", "8.8.8.8", "2001:4860:4860::8888", "external.example", "100.63.255.255", "100.128.0.1"}) {
            assertFalse(policy.allowedPeer(peer), peer);
        }
        assertTrue(policy.loopback("127.0.0.1"));
        assertFalse(policy.loopback("192.168.10.20"));
    }

    @Test void doesNotTrustExternalOriginOrArbitraryHost() {
        var policy = new NetworkPolicy(8087);
        assertTrue(policy.allowedOrigin("http://localhost:8087", "localhost"));
        assertTrue(policy.allowedOrigin("http://127.0.0.1:5173", "127.0.0.1"));
        assertFalse(policy.allowedOrigin("https://external.example", "localhost"));
        assertFalse(policy.allowedHost("external.example"));
        assertDoesNotThrow(() -> new NetworkPolicy(5173));
    }
}
