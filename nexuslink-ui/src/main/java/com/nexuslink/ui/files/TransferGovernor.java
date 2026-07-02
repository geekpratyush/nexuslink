package com.nexuslink.ui.files;

import java.util.function.LongSupplier;

/**
 * Pause/resume + bandwidth-throttle control for the {@link TransferQueue}, applied at the one place
 * every {@link FileTransfer} implementation already reports through: the cumulative-bytes progress
 * callback (called per 64 KB chunk). {@link #tick} is invoked once per chunk on the transfer thread —
 * it blocks while paused and sleeps as needed to keep the average rate under the configured limit, so
 * no protocol code (SFTP/FTP/local) has to know about pausing or throttling.
 *
 * <p>JavaFX-free and unit-testable: the clock (nanoseconds) and the throttle {@link Sleeper} are
 * injectable, so timing behaviour can be asserted deterministically without real transfers.</p>
 */
public final class TransferGovernor {

    /** Sleeps for the given number of milliseconds; injectable so tests can record instead of wait. */
    @FunctionalInterface
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final LongSupplier clockNanos;
    private final Sleeper sleeper;
    private final Object pauseLock = new Object();

    private volatile boolean paused;
    private volatile long maxBytesPerSecond;   // 0 = unlimited

    // Throttle baseline for the current transfer (reset by startTransfer()).
    private long baseNanos = -1;
    private long baseBytes;

    public TransferGovernor() {
        this(System::nanoTime, Thread::sleep);
    }

    TransferGovernor(LongSupplier clockNanos, Sleeper sleeper) {
        this.clockNanos = clockNanos;
        this.sleeper = sleeper;
    }

    // ---- controls (any thread) ----

    public void pause() { paused = true; }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    public boolean isPaused() { return paused; }

    /** Sets the ceiling in bytes/second; {@code <= 0} means unlimited. */
    public void setMaxBytesPerSecond(long limit) {
        this.maxBytesPerSecond = Math.max(0, limit);
    }

    public long maxBytesPerSecond() { return maxBytesPerSecond; }

    /** Resets the throttle baseline; call at the start of each file so rates are measured per-transfer. */
    public void startTransfer() {
        baseNanos = -1;
    }

    // ---- per-chunk governing (transfer thread) ----

    /**
     * Governs one progress tick for a transfer that has moved {@code cumulativeBytes} so far: blocks
     * while paused, then sleeps just enough to keep the average throughput under the limit. Never
     * throws — an interrupt is preserved on the thread and the method returns so the caller can react.
     */
    public void tick(long cumulativeBytes) {
        try {
            awaitResume();
            throttle(cumulativeBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitResume() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused) pauseLock.wait();
        }
    }

    private void throttle(long cumulativeBytes) throws InterruptedException {
        long limit = maxBytesPerSecond;
        if (limit <= 0) return;
        long now = clockNanos.getAsLong();
        if (baseNanos < 0) {                 // first governed chunk of this transfer
            baseNanos = now;
            baseBytes = cumulativeBytes;
            return;
        }
        long bytes = cumulativeBytes - baseBytes;
        if (bytes <= 0) return;
        long targetNanos = Math.round(bytes * 1_000_000_000.0 / limit);
        long elapsedNanos = now - baseNanos;
        long sleepMillis = (targetNanos - elapsedNanos) / 1_000_000L;
        if (sleepMillis > 0) sleeper.sleep(sleepMillis);
    }
}
