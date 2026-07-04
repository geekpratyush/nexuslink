package com.nexuslink.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.nexuslink.core.diagnostics.ConnectionDiagnostics.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionDiagnosticsTest {

    private static ConnectionDiagnostics.Step ok(String name, String detail) {
        return new ConnectionDiagnostics.Step(name, () -> detail);
    }

    private static ConnectionDiagnostics.Step fail(String name, String message) {
        return new ConnectionDiagnostics.Step(name, () -> { throw new RuntimeException(message); });
    }

    @Test
    void allStepsPassInOrder() {
        List<ConnectionDiagnostics.StepResult> r = ConnectionDiagnostics.run(List.of(
                ok("DNS", "resolved 10.0.0.1"), ok("TCP", "connected"), ok("TLS", "handshake ok")));
        assertEquals(List.of("DNS", "TCP", "TLS"), r.stream().map(ConnectionDiagnostics.StepResult::name).toList());
        assertTrue(ConnectionDiagnostics.allPassed(r));
        assertEquals("resolved 10.0.0.1", r.get(0).detail());
    }

    @Test
    void firstFailureStopsAndRestAreSkipped() {
        List<ConnectionDiagnostics.StepResult> r = ConnectionDiagnostics.run(List.of(
                ok("DNS", "ok"), fail("TCP", "connection refused"), ok("TLS", "ok"), ok("Admin", "ok")));
        assertEquals(PASSED, r.get(0).status());
        assertEquals(FAILED, r.get(1).status());
        assertEquals("connection refused", r.get(1).detail());
        assertEquals(SKIPPED, r.get(2).status());
        assertEquals(SKIPPED, r.get(3).status());
        assertFalse(ConnectionDiagnostics.allPassed(r));
    }

    @Test
    void probesAfterFailureAreNotRun() {
        boolean[] ran = {false};
        ConnectionDiagnostics.Step guarded = new ConnectionDiagnostics.Step("TLS", () -> { ran[0] = true; return "ok"; });
        ConnectionDiagnostics.run(List.of(fail("TCP", "refused"), guarded));
        assertFalse(ran[0], "a step after a failure must not be probed");
    }

    @Test
    void callbackFiresOncePerStepInOrder() {
        List<String> seen = new ArrayList<>();
        ConnectionDiagnostics.run(List.of(ok("A", "a"), fail("B", "boom"), ok("C", "c")),
                res -> seen.add(res.name() + ":" + res.status()));
        assertEquals(List.of("A:PASSED", "B:FAILED", "C:SKIPPED"), seen);
    }

    @Test
    void nullExceptionMessageStillProducesDetail() {
        ConnectionDiagnostics.Step npe = new ConnectionDiagnostics.Step("X", () -> { throw new NullPointerException(); });
        List<ConnectionDiagnostics.StepResult> r = ConnectionDiagnostics.run(List.of(npe));
        assertEquals(FAILED, r.get(0).status());
        assertNotNull(r.get(0).detail());
        assertFalse(r.get(0).detail().isBlank());
    }

    @Test
    void emptyRunIsVacuouslyPassed() {
        List<ConnectionDiagnostics.StepResult> r = ConnectionDiagnostics.run(List.of());
        assertTrue(r.isEmpty());
        assertTrue(ConnectionDiagnostics.allPassed(r));
    }

    @Test
    void elapsedIsNonNegative() {
        List<ConnectionDiagnostics.StepResult> r = ConnectionDiagnostics.run(List.of(ok("A", "a")));
        assertTrue(r.get(0).elapsedMs() >= 0);
    }
}
