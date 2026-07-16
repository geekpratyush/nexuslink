package com.nexuslink.ui.files;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One queued file transfer in a {@link TransferQueue}. A plain (JavaFX-free) data holder so the
 * queue logic can be unit-tested without a display; the {@link TransferQueuePanel} observes these
 * via the queue's listeners and renders per-row progress.
 *
 * <p>For an {@link Direction#UPLOAD} the {@code source} is a local {@link FileItem} and
 * {@code destDir} is a remote directory; for a {@link Direction#DOWNLOAD} the {@code source} is a
 * remote {@link FileItem} and {@code destDir} is a local directory.</p>
 */
public final class TransferItem {

    /** Transfer direction relative to the two-pane browser (local ⇄ remote). */
    public enum Direction { UPLOAD, DOWNLOAD }

    private static final AtomicLong SEQ = new AtomicLong();

    private final long id = SEQ.incrementAndGet();
    private final Direction direction;
    private final FileItem source;
    private final String destDir;
    private final long totalBytes;
    private final OverwriteResolver resolver;
    private final boolean moveMode;   // when true, the source is deleted after a successful copy (a move)

    // The name the file actually lands under; equals the source name unless a "rename on conflict"
    // resolution redirected it to a non-colliding name.
    private volatile String destName;

    private volatile TransferStatus status = TransferStatus.QUEUED;
    private volatile long transferredBytes;
    private volatile int attempts;   // started attempts, incremented each time the worker begins this item
    private volatile String error;
    private volatile long startNanos = -1;   // set when the transfer goes ACTIVE
    private volatile long endNanos = -1;      // set when it reaches a terminal state

    public TransferItem(Direction direction, FileItem source, String destDir, OverwriteResolver resolver) {
        this(direction, source, destDir, resolver, false);
    }

    public TransferItem(Direction direction, FileItem source, String destDir, OverwriteResolver resolver,
                        boolean moveMode) {
        this.direction = direction;
        this.source = source;
        this.destDir = destDir;
        this.totalBytes = Math.max(source.size(), 0);
        this.resolver = resolver == null ? OverwriteResolver.alwaysOverwrite() : resolver;
        this.moveMode = moveMode;
    }

    public long id() { return id; }
    public Direction direction() { return direction; }
    public FileItem source() { return source; }
    public String name() { return source.name(); }
    public String destDir() { return destDir; }

    /** The name the file lands under — the source name unless a rename-on-conflict redirected it. */
    public String destName() { return destName == null ? source.name() : destName; }
    /** Redirects the landing name (rename-on-conflict); the source name is unchanged. */
    void setDestName(String destName) { this.destName = destName; }
    public long totalBytes() { return totalBytes; }
    public OverwriteResolver resolver() { return resolver; }

    /** True when this transfer is a move: the source is deleted once the copy completes successfully. */
    public boolean isMove() { return moveMode; }

    public TransferStatus status() { return status; }
    void setStatus(TransferStatus status) { this.status = status; }

    /** Resets a terminal item back to QUEUED so the worker will pick it up again (manual retry:
     * also clears the auto-retry attempt budget). */
    void resetForRetry() {
        requeue();
        this.attempts = 0;
    }

    /** Re-queues after a transient auto-retry, preserving the attempt count (and last error). */
    void requeueForAutoRetry() {
        requeue();
    }

    private void requeue() {
        this.status = TransferStatus.QUEUED;
        this.transferredBytes = 0;
        this.error = null;
        this.startNanos = -1;
        this.endNanos = -1;
    }

    /** Number of attempts started so far; incremented as the worker begins each try. */
    public int attempts() { return attempts; }
    void beginAttempt() { this.attempts++; }

    public long transferredBytes() { return transferredBytes; }
    void setTransferredBytes(long bytes) { this.transferredBytes = bytes; }

    public String error() { return error; }
    void setError(String error) { this.error = error; }

    // ---- timing / speed / ETA ----

    /** Records the moment the transfer became active (nanoTime). */
    void markStarted(long nanos) { this.startNanos = nanos; }
    /** Records the moment the transfer reached a terminal state (nanoTime). */
    void markFinished(long nanos) { this.endNanos = nanos; }

    /** Elapsed transfer time in nanos: 0 before start, frozen at the finish time once terminal. */
    long elapsedNanos(long nowNanos) {
        if (startNanos < 0) return 0;
        long end = endNanos >= 0 ? endNanos : nowNanos;
        return Math.max(0, end - startNanos);
    }

    /** Current throughput in bytes/second ({@code 0} until enough has elapsed to be meaningful). */
    public double bytesPerSecond(long nowNanos) {
        return rate(transferredBytes, elapsedNanos(nowNanos));
    }

    /**
     * Estimated seconds remaining for an in-flight transfer, or {@code -1} when unknown (not active,
     * unknown size, or no measurable rate yet). Terminal items return {@code 0}.
     */
    public long etaSeconds(long nowNanos) {
        if (status.terminal()) return 0;
        if (status != TransferStatus.ACTIVE || totalBytes <= 0) return -1;
        double bps = bytesPerSecond(nowNanos);
        return etaSeconds(totalBytes - transferredBytes, bps);
    }

    /** Pure rate helper: bytes over an elapsed nanosecond window ({@code 0} when no time elapsed). */
    public static double rate(long bytes, long elapsedNanos) {
        if (elapsedNanos <= 0 || bytes <= 0) return 0;
        return bytes / (elapsedNanos / 1_000_000_000.0);
    }

    /** Pure ETA helper: remaining bytes at a rate, rounded up; {@code -1} when the rate is unusable. */
    public static long etaSeconds(long remainingBytes, double bytesPerSecond) {
        if (bytesPerSecond <= 0 || remainingBytes < 0) return -1;
        return (long) Math.ceil(remainingBytes / bytesPerSecond);
    }

    /** Formats a byte rate as a human string, e.g. {@code "1.2 MB/s"}; blank for a zero rate. */
    public static String formatRate(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        double s = bytesPerSecond;
        int u = 0;
        while (s >= 1024 && u < units.length - 1) { s /= 1024; u++; }
        return (u == 0 ? String.format("%.0f %s", s, units[u]) : String.format("%.1f %s", s, units[u]));
    }

    /** Formats an ETA in seconds as {@code "--"} (unknown), {@code "42s"}, or {@code "3m07s"}. */
    public static String formatEta(long seconds) {
        if (seconds < 0) return "--";
        if (seconds < 60) return seconds + "s";
        long m = seconds / 60, s = seconds % 60;
        if (m < 60) return String.format("%dm%02ds", m, s);
        long h = m / 60; m %= 60;
        return String.format("%dh%02dm", h, m);
    }

    /** Fraction transferred in {@code [0,1]}; terminal items report 1.0 so the bar settles full. */
    public double progress() {
        if (status.terminal()) return 1.0;
        if (totalBytes <= 0) return 0.0;
        return Math.min(1.0, (double) transferredBytes / totalBytes);
    }

    /** Bytes counted as finished for overall accounting (full size once terminal). */
    long accountedBytes() {
        return status.terminal() ? Math.max(totalBytes, 1) : transferredBytes;
    }

    /** Total size for overall accounting (at least 1 so a zero-byte file still counts). */
    long weight() {
        return Math.max(totalBytes, 1);
    }
}
