package com.nexuslink.security.cert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A background watchdog that warns when stored certificates approach (or pass) their expiry.
 * It periodically scans a {@link CertificateInfo} source and fires an {@link Alert} the first time
 * a certificate crosses each configured threshold (default 30 / 7 / 1 days) and again when it
 * actually expires. Alerts escalate but never repeat for the same stage, so listeners — the cert
 * list colouring, a status-bar badge, a log line — are notified exactly once per crossing.
 *
 * <p>The scan logic is clock-injectable and side-effect free ({@link #scan(Instant)} returns the
 * newly-fired alerts), so it is fully unit-testable without waiting on wall-clock time.
 */
public final class ExpirationWatchdog {

    /** Severity of an alert, derived from which threshold was crossed. */
    public enum Level { WARNING, CRITICAL, EXPIRED }

    /** Default warning thresholds, in days, from least to most urgent. */
    public static final int[] DEFAULT_THRESHOLDS = {30, 7, 1};

    /** A single expiry notification for one certificate crossing one threshold. */
    public record Alert(String alias, String commonName, long daysUntilExpiry,
                        int thresholdDays, Level level, Instant notAfter) {

        /** A human-readable, status-bar-friendly summary. */
        public String message() {
            return switch (level) {
                case EXPIRED -> "Certificate '" + alias + "' (" + commonName + ") has EXPIRED";
                default -> "Certificate '" + alias + "' (" + commonName + ") expires in "
                        + daysUntilExpiry + " day" + (daysUntilExpiry == 1 ? "" : "s");
            };
        }
    }

    private final Supplier<Map<String, CertificateInfo>> source;
    private final int[] thresholds;          // sorted ascending (most urgent first), e.g. [1, 7, 30]
    private final Clock clock;
    private final List<Consumer<Alert>> listeners = new CopyOnWriteArrayList<>();

    /** alias -> highest stage already fired (0 = none, N+1 = expired); higher is more urgent. */
    private final Map<String, Integer> firedStage = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public ExpirationWatchdog(Supplier<Map<String, CertificateInfo>> source) {
        this(source, DEFAULT_THRESHOLDS, Clock.systemUTC());
    }

    public ExpirationWatchdog(Supplier<Map<String, CertificateInfo>> source, int[] thresholds, Clock clock) {
        this.source = source;
        // Keep thresholds ascending; the nearest-expiry match maps to the highest stage (see stageFor),
        // so a later scan that has crept closer to expiry always escalates rather than de-escalates.
        int[] sorted = thresholds.clone();
        java.util.Arrays.sort(sorted);
        this.thresholds = sorted;
        this.clock = clock;
    }

    /** Registers a listener invoked (on the scanning thread) for every newly-fired alert. */
    public void addListener(Consumer<Alert> listener) {
        if (listener != null) listeners.add(listener);
    }

    /**
     * Scans the source once and returns the alerts that are newly triggered at {@code now}.
     * Each returned alert is also dispatched to every registered listener. Calling this again with
     * the same (or a nearer) instant produces no duplicate alerts unless a cert has escalated to a
     * more urgent threshold.
     */
    public List<Alert> scan(Instant now) {
        Map<String, CertificateInfo> certs = source.get();
        List<Alert> fired = new ArrayList<>();
        if (certs == null) return fired;

        // Forget aliases that have been removed so a re-imported alias re-evaluates from scratch.
        firedStage.keySet().retainAll(certs.keySet());

        for (Map.Entry<String, CertificateInfo> e : certs.entrySet()) {
            String alias = e.getKey();
            CertificateInfo info = e.getValue();
            if (info == null) continue;

            int stage = stageFor(info, now);
            if (stage == 0) {
                // Not yet inside any threshold (or not yet valid) — clear any stale state.
                firedStage.remove(alias);
                continue;
            }
            int previous = firedStage.getOrDefault(alias, 0);
            if (stage <= previous) continue; // already alerted at this or a more urgent stage

            firedStage.put(alias, stage);
            fired.add(toAlert(alias, info, now, stage));
        }

        for (Alert a : fired) {
            for (Consumer<Alert> l : listeners) {
                try { l.accept(a); } catch (RuntimeException ignored) { /* never let one listener break the scan */ }
            }
        }
        return fired;
    }

    /**
     * The urgency stage of a certificate at {@code now}: 0 if outside every threshold (or not yet
     * valid), 1..N for the matched thresholds (1 = most urgent), N+1 if already expired.
     */
    private int stageFor(CertificateInfo info, Instant now) {
        if (now.isBefore(info.notBefore())) return 0;          // not yet valid → don't warn
        if (!now.isBefore(info.notAfter())) return thresholds.length + 1; // expired
        long days = ChronoUnit.DAYS.between(now, info.notAfter());
        for (int i = 0; i < thresholds.length; i++) {
            // ascending array → the first (smallest) matching threshold is the nearest to expiry;
            // map it to the highest stage so urgency increases monotonically with the stage number.
            if (days <= thresholds[i]) return thresholds.length - i;
        }
        return 0;
    }

    private Alert toAlert(String alias, CertificateInfo info, Instant now, int stage) {
        long days = ChronoUnit.DAYS.between(now, info.notAfter());
        boolean expired = stage == thresholds.length + 1;
        if (expired) {
            return new Alert(alias, info.commonName(), days, 0, Level.EXPIRED, info.notAfter());
        }
        int threshold = thresholds[thresholds.length - stage];
        Level level = threshold <= 7 ? Level.CRITICAL : Level.WARNING;
        return new Alert(alias, info.commonName(), days, threshold, level, info.notAfter());
    }

    /** Aliases currently expiring within the broadest threshold (or already expired). */
    public Set<String> aliasesNeedingAttention() {
        return Set.copyOf(firedStage.keySet());
    }

    /** Starts a daemon thread that scans immediately and then every {@code interval}. */
    public synchronized void start(Duration interval) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cert-expiration-watchdog");
            t.setDaemon(true);
            return t;
        });
        long millis = Math.max(1000L, interval.toMillis());
        scheduler.scheduleAtFixedRate(() -> {
            try { scan(clock.instant()); } catch (RuntimeException ignored) { /* keep the watchdog alive */ }
        }, 0, millis, TimeUnit.MILLISECONDS);
    }

    /** Stops the background scanner; the watchdog can be {@link #start(Duration) started} again. */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Clears remembered alert state so the next scan re-evaluates every certificate afresh. */
    public void reset() {
        firedStage.clear();
    }
}
