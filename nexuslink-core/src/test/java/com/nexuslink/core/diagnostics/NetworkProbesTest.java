package com.nexuslink.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;

import static com.nexuslink.core.diagnostics.ConnectionDiagnostics.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkProbesTest {

    @Test
    void dnsResolvesLocalhost() throws Exception {
        String detail = NetworkProbes.dnsResolve("localhost").probe();
        assertTrue(detail.startsWith("resolved"), detail);
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
