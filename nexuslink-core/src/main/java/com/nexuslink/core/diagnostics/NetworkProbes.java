package com.nexuslink.core.diagnostics;

import com.nexuslink.core.net.DnsCache;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Concrete network {@link ConnectionDiagnostics.Probe}s — DNS resolution, a TCP connect, and a TLS
 * handshake — plus a helper that assembles the common DNS→TCP[→TLS] step list for a {@code host:port}.
 * These do real blocking I/O (so callers run them off the UI thread); the pure sequencing lives in
 * {@link ConnectionDiagnostics}. Each probe returns a short human detail on success and throws on
 * failure, which {@link ConnectionDiagnostics} turns into a FAILED step whose detail is the message.
 */
public final class NetworkProbes {

    private NetworkProbes() {}

    /** Process-wide short-TTL DNS cache (30s) shared by every diagnostics run. */
    private static final DnsCache SHARED_DNS = DnsCache.standard();

    /** Resolves {@code host} to one or more IP addresses, reusing the shared 30s DNS cache. */
    public static ConnectionDiagnostics.Probe dnsResolve(String host) {
        return dnsResolve(host, SHARED_DNS);
    }

    /** Resolves {@code host} through a specific {@link DnsCache} (used by tests). */
    public static ConnectionDiagnostics.Probe dnsResolve(String host, DnsCache cache) {
        return () -> {
            long hitsBefore = cache.hitCount();
            List<InetAddress> addrs = cache.resolve(host);
            boolean cached = cache.hitCount() > hitsBefore;
            return "resolved " + addrs.size() + " address(es) · " + addrs.get(0).getHostAddress()
                    + (cached ? " (cached)" : "");
        };
    }

    /** Opens a plain TCP connection to {@code host:port} within {@code timeoutMs}. */
    public static ConnectionDiagnostics.Probe tcpConnect(String host, int port, int timeoutMs) {
        return () -> {
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
                return "connected to " + host + ":" + port + " in " + ms + " ms";
            }
        };
    }

    /** Completes a TLS handshake with {@code host:port} and reports the negotiated protocol + cipher. */
    public static ConnectionDiagnostics.Probe tlsHandshake(String host, int port, int timeoutMs) {
        return () -> {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                socket.startHandshake();
                return "TLS " + socket.getSession().getProtocol() + " · " + socket.getSession().getCipherSuite();
            }
        };
    }

    /**
     * The common step list for reaching a {@code host:port}: DNS then TCP, plus a TLS handshake when
     * {@code tls} is true. Protocol-specific steps (SASL auth, an admin API call) are appended by callers.
     */
    public static List<ConnectionDiagnostics.Step> basicSteps(String host, int port, boolean tls, int timeoutMs) {
        List<ConnectionDiagnostics.Step> steps = new ArrayList<>();
        steps.add(new ConnectionDiagnostics.Step("DNS", dnsResolve(host)));
        steps.add(new ConnectionDiagnostics.Step("TCP", tcpConnect(host, port, timeoutMs)));
        if (tls) steps.add(new ConnectionDiagnostics.Step("TLS", tlsHandshake(host, port, timeoutMs)));
        return steps;
    }
}
