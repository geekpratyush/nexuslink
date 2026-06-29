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

    private volatile TransferStatus status = TransferStatus.QUEUED;
    private volatile long transferredBytes;
    private volatile String error;

    public TransferItem(Direction direction, FileItem source, String destDir, OverwriteResolver resolver) {
        this.direction = direction;
        this.source = source;
        this.destDir = destDir;
        this.totalBytes = Math.max(source.size(), 0);
        this.resolver = resolver == null ? OverwriteResolver.alwaysOverwrite() : resolver;
    }

    public long id() { return id; }
    public Direction direction() { return direction; }
    public FileItem source() { return source; }
    public String name() { return source.name(); }
    public String destDir() { return destDir; }
    public long totalBytes() { return totalBytes; }
    public OverwriteResolver resolver() { return resolver; }

    public TransferStatus status() { return status; }
    void setStatus(TransferStatus status) { this.status = status; }

    public long transferredBytes() { return transferredBytes; }
    void setTransferredBytes(long bytes) { this.transferredBytes = bytes; }

    public String error() { return error; }
    void setError(String error) { this.error = error; }

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
