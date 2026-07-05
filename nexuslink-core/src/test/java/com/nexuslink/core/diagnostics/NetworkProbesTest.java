package com.nexuslink.core.diagnostics;

import com.nexuslink.core.net.DnsCache;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexuslink.core.diagnostics.ConnectionDiagnostics.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkProbesTest {

    @Test
    void dnsResolvesLocalhost() throws Exception {
        String detail = NetworkProbes.dnsResolve("localhost").probe();
        assertTrue(detail.startsWith("resolved"), detail);
    }

    @Test
    void dnsProbeReusesTheCacheOnASecondRun() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DnsCache cache = DnsCache.withTtl(Duration.ofMinutes(1), host -> {
            calls.incrementAndGet();
            return List.of(InetAddress.getByAddress(new byte[]{10, 0, 0, 1}));
        });
        String first = NetworkProbes.dnsResolve("host.local", cache).probe();
        String second = NetworkProbes.dnsResolve("host.local", cache).probe();
        assertEquals(1, calls.get(), "second probe served from cache");
        assertFalse(first.contains("(cached)"), first);
        assertTrue(second.contains("(cached)"), second);
    }

    @Test
    void dnsFailsForAReservedInvalidName() {
        // ".invalid" is reserved (RFC 2606) and must never resolve.
        assertThrows(Exception.class, () -> NetworkProbes.dnsResolve("nexuslink.no-such-host.invalid").probe());
    }

    @Test
    void tcpConnectsToAnOpenLocalPort() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            String detail = NetworkProbes.tcpConnect("127.0.0.1", port, 2000).probe();
            assertTrue(detail.contains("connected"), detail);
        }
    }

    @Test
    void tcpFailsToAClosedPort() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }   // closed here → nothing is listening on that port
        assertThrows(Exception.class, () -> NetworkProbes.tcpConnect("127.0.0.1", port, 1000).probe());
    }

    @Test
    void basicStepsAreDnsTcpAndOptionallyTls() {
        List<ConnectionDiagnostics.Step> plain = NetworkProbes.basicSteps("host", 1234, false, 500);
        assertEquals(List.of("DNS", "TCP"), plain.stream().map(ConnectionDiagnostics.Step::name).toList());

        List<ConnectionDiagnostics.Step> secure = NetworkProbes.basicSteps("host", 1234, true, 500);
        assertEquals(List.of("DNS", "TCP", "TLS"), secure.stream().map(ConnectionDiagnostics.Step::name).toList());
    }

    @Test
    void runnerDrivesRealProbesEndToEnd() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            List<ConnectionDiagnostics.StepResult> r = ConnectionDiagnostics.run(
                    NetworkProbes.basicSteps("127.0.0.1", server.getLocalPort(), false, 2000));
            assertTrue(ConnectionDiagnostics.allPassed(r), r.toString());
            assertEquals(PASSED, r.get(0).status());   // DNS
            assertEquals(PASSED, r.get(1).status());   // TCP
        }
    }
}
