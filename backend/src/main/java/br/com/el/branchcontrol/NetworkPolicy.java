package br.com.el.branchcontrol;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;

@Component
public class NetworkPolicy {
    private final int port;
    public NetworkPolicy(@Value("${server.port:8087}") int port) { this.port = port; }

    private InetAddress literal(String value) {
        if (value == null) return null;
        String address = value.replaceAll("^\\[|\\]$", "").replaceFirst("%.*$", "");
        if (!(address.contains(":") || address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return null;
        try { return InetAddress.getByName(address); } catch (Exception ex) { return null; }
    }

    public boolean allowedPeer(String value) {
        InetAddress address = literal(value);
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        // RFC 6598 shared address space is used by the local corporate network.
        boolean sharedV4 = bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || uniqueLocalV6 || sharedV4;
    }

    public boolean loopback(String value) {
        InetAddress address = literal(value);
        return address != null && address.isLoopbackAddress();
    }

    public Set<String> localHosts() {
        Set<String> hosts = new HashSet<>(List.of("localhost", "127.0.0.1", "::1"));
        String computer = System.getenv("COMPUTERNAME");
        if (computer != null && computer.matches("[A-Za-z0-9._-]+")) hosts.add(computer.toLowerCase(Locale.ROOT));
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) for (var network : Collections.list(interfaces)) {
                for (var address : Collections.list(network.getInetAddresses())) {
                    if (allowedPeer(address.getHostAddress())) hosts.add(address.getHostAddress().replaceFirst("%.*$", "").toLowerCase(Locale.ROOT));
                }
            }
        } catch (SocketException ignored) { }
        return Set.copyOf(hosts);
    }

    public boolean allowedHost(String hostname) {
        if (hostname == null) return false;
        return localHosts().contains(hostname.replaceAll("^\\[|\\]$", "").toLowerCase(Locale.ROOT));
    }

    public boolean allowedOrigin(String origin, String requestHostname) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            int originPort = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            String normalized = host == null ? "" : host.replaceAll("^\\[|\\]$", "");
            return List.of("http", "https").contains(uri.getScheme()) &&
                    uri.getRawUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty()) &&
                    uri.getQuery() == null && uri.getFragment() == null &&
                    normalized.equalsIgnoreCase(requestHostname.replaceAll("^\\[|\\]$", "")) &&
                    allowedHost(normalized) && (originPort == port || originPort == 5173);
        } catch (RuntimeException ex) { return false; }
    }

    public List<String> urls() {
        return localHosts().stream().filter(host -> {
                    InetAddress address = literal(host);
                    return address != null && address.getAddress().length == 4 && !address.isLoopbackAddress() && !address.isLinkLocalAddress();
                })
                .sorted().map(host -> "http://" + host + ":" + port).toList();
    }
}
