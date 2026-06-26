package com.nexuslink.security.cert;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ExpirationWatchdogTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    /** A minimal CertificateInfo that expires {@code fromNow} after {@link #T0}. */
    private static CertificateInfo expiringIn(Duration fromNow) {
        Instant notAfter = T0.plus(fromNow);
        return new CertificateInfo("CN=test", "CN=test", "1",
                T0.minus(Duration.ofDays(365)), notAfter, List.of(),
                "RSA", 2048, "SHA256withRSA", true, false, "ab:cd");
    }

    private static Map<String, CertificateInfo> source(CertificateInfo info) {
        Map<String, CertificateInfo> m = new LinkedHashMap<>();
        m.put("cert", info);
        return m;
    }

    @Test
    void firesNothingForCertWellBeyondThresholds() {
        var wd = new ExpirationWatchdog(() -> source(expiringIn(Duration.ofDays(90))));
        assertTrue(wd.scan(T0).isEmpty());
    }

    @Test
    void firesOncePerThresholdAndEscalates() {
        Map<String, CertificateInfo> live = source(expiringIn(Duration.ofDays(25)));
        var wd = new ExpirationWatchdog(() -> live);

        // 25 days out → inside the 30-day window → one WARNING.
        List<ExpirationWatchdog.Alert> first = wd.scan(T0);
        assertEquals(1, first.size());
        assertEquals(30, first.get(0).thresholdDays());
        assertEquals(ExpirationWatchdog.Level.WARNING, first.get(0).level());

        // Re-scan at the same point → no duplicate.
        assertTrue(wd.scan(T0).isEmpty());

        // Advance to 5 days before expiry → escalates to the 7-day CRITICAL.
        List<ExpirationWatchdog.Alert> second = wd.scan(T0.plus(Duration.ofDays(20)));
        assertEquals(1, second.size());
        assertEquals(7, second.get(0).thresholdDays());
        assertEquals(ExpirationWatchdog.Level.CRITICAL, second.get(0).level());

        // Advance past expiry → one EXPIRED alert, then nothing more.
        List<ExpirationWatchdog.Alert> third = wd.scan(T0.plus(Duration.ofDays(30)));
        assertEquals(1, third.size());
        assertEquals(ExpirationWatchdog.Level.EXPIRED, third.get(0).level());
        assertTrue(wd.scan(T0.plus(Duration.ofDays(31))).isEmpty());
    }

    @Test
    void skipsTheOneDayStageWhenJumpingStraightPastIt() {
        // A scan that lands inside the 1-day window without ever seeing 30/7 should still fire once.
        var wd = new ExpirationWatchdog(() -> source(expiringIn(Duration.ofHours(12))));
        List<ExpirationWatchdog.Alert> fired = wd.scan(T0);
        assertEquals(1, fired.size());
        assertEquals(1, fired.get(0).thresholdDays());
        assertEquals(ExpirationWatchdog.Level.CRITICAL, fired.get(0).level());
    }

    @Test
    void ignoresNotYetValidCerts() {
        CertificateInfo future = new CertificateInfo("CN=future", "CN=future", "1",
                T0.plus(Duration.ofDays(10)), T0.plus(Duration.ofDays(20)), List.of(),
                "EC", 256, "SHA256withECDSA", true, false, "ff");
        var wd = new ExpirationWatchdog(() -> source(future));
        assertTrue(wd.scan(T0).isEmpty(), "a cert that isn't valid yet should not warn about expiry");
    }

    @Test
    void notifiesRegisteredListeners() {
        var wd = new ExpirationWatchdog(() -> source(expiringIn(Duration.ofDays(3))));
        var seen = new CopyOnWriteArrayList<ExpirationWatchdog.Alert>();
        wd.addListener(seen::add);
        wd.scan(T0);
        assertEquals(1, seen.size());
        assertEquals("cert", seen.get(0).alias());
    }

    @Test
    void forgetsRemovedAliasesSoReimportReevaluates() {
        Map<String, CertificateInfo> live = source(expiringIn(Duration.ofDays(25)));
        var wd = new ExpirationWatchdog(() -> live);
        assertEquals(1, wd.scan(T0).size());

        // Remove the cert, scan (state pruned), then re-add the same alias → it warns again.
        live.clear();
        assertTrue(wd.scan(T0).isEmpty());
        live.putAll(source(expiringIn(Duration.ofDays(25))));
        assertEquals(1, wd.scan(T0).size(), "re-imported alias should warn afresh");
    }
}
