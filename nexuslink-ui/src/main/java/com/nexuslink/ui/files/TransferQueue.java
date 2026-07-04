package com.nexuslink.ui.files;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Sequentially drives the existing {@link FileTransfer} copy logic for a batch of {@link TransferItem}s,
 * exposing observable per-item and overall progress. The mechanics of moving bytes are NOT
 * re-implemented here — each item is dispatched to {@link FileTransfer#upload}/{@link FileTransfer#download}.
 *
 * <p>This class is JavaFX-free and therefore unit-testable: tests drive it synchronously via
 * {@link #runPending()} with a {@link LocalFileSystem} on both sides, while the UI calls
 * {@link #startWorker()} once so transfers run on a background thread.</p>
 */
public final class TransferQueue {

    /** Receives queue change notifications (off any thread — UI listeners must marshal to FX). */
    public interface Listener {
        /** A structural change: an item was added/removed/cleared or changed status. */
        void onQueueChanged();
        /** Frequent byte-level progress for one active item. */
        default void onItemProgress(TransferItem item) {}
    }

    private final FileSystem local;
    private final FileSystem remote;
    private final FileTransfer transfer;

    private final List<TransferItem> items = new ArrayList<>();   // guarded by lock
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private Consumer<TransferItem> onItemFinished = i -> {};
    private final TransferGovernor governor = new TransferGovernor();
    private volatile boolean workerRunning;
    private volatile boolean verifyIntegrity;   // when set, each completed file is size-checked on the destination
    private Thread worker;

    public TransferQueue(FileSystem local, FileSystem remote, FileTransfer transfer) {
        this.local = local;
        this.remote = remote;
        this.transfer = transfer;
    }

    // ---- observation ----

    public void addListener(Listener l) { if (l != null) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Invoked once per item when it reaches a terminal state (DONE/SKIPPED/FAILED). */
    public void setOnItemFinished(Consumer<TransferItem> handler) {
        this.onItemFinished = handler == null ? i -> {} : handler;
    }

    /** An immutable snapshot of the current items, in insertion order. */
    public List<TransferItem> items() {
        synchronized (lock) { return List.copyOf(items); }
    }

    // ---- enqueueing ----

    /** Enqueues a batch sharing one {@link OverwriteResolver}, returning the created items. */
    public List<TransferItem> enqueue(TransferItem.Direction direction, List<FileItem> sources,
                                      String destDir, OverwriteResolver resolver) {
        List<TransferItem> created = new ArrayList<>();
        synchronized (lock) {
            for (FileItem f : sources) {
                if (f.parent() || f.directory()) continue;
                TransferItem item = new TransferItem(direction, f, destDir, resolver);
                items.add(item);
                created.add(item);
            }
            lock.notifyAll();
        }
        if (!created.isEmpty()) fireChanged();
        return created;
    }

    /**
     * Expands any directories in {@code sources} recursively and enqueues every file they contain,
     * recreating the folder structure under {@code destRootDir} on the destination side; plain files
     * are enqueued straight into {@code destRootDir}. The source tree is read via the appropriate
     * {@link FileSystem} ({@code local} for an upload, {@code remote} for a download) and the matching
     * destination directories are created with {@link FileSystem#mkdir} as needed.
     *
     * <p>Performs blocking I/O (listing the source tree + creating destination directories), so
     * callers must invoke it off the UI thread. Directories are created up-front; the enqueued files
     * then flow through the normal sequential worker with the shared {@code resolver}.</p>
     */
    public List<TransferItem> enqueueRecursive(TransferItem.Direction direction, List<FileItem> sources,
                                               String destRootDir, OverwriteResolver resolver) throws Exception {
        FileSystem sourceFs = direction == TransferItem.Direction.UPLOAD ? local : remote;
        FileSystem destFs = direction == TransferItem.Direction.UPLOAD ? remote : local;
        List<TransferItem> planned = new ArrayList<>();
        for (FileItem f : sources) {
            if (f.parent()) continue;
            expand(direction, f, destRootDir, resolver, sourceFs, destFs, planned);
        }
        if (!planned.isEmpty()) {
            synchronized (lock) {
                items.addAll(planned);
                lock.notifyAll();
            }
            fireChanged();
        }
        return planned;
    }

    /** Recursively walks {@code src}, creating destination sub-directories and collecting file items. */
    private void expand(TransferItem.Direction direction, FileItem src, String destDir,
                        OverwriteResolver resolver, FileSystem sourceFs, FileSystem destFs,
                        List<TransferItem> out) throws Exception {
        if (!src.directory()) {
            out.add(new TransferItem(direction, src, destDir, resolver));
            return;
        }
        String childDest = destFs.join(destDir, src.name());
        if (!destFs.exists(destDir, src.name())) destFs.mkdir(childDest);
        for (FileItem child : sourceFs.list(src.path())) {
            if (child.parent()) continue;
            expand(direction, child, childDest, resolver, sourceFs, destFs, out);
        }
    }

    /** Removes all terminal (DONE/SKIPPED/FAILED/CANCELLED) items. */
    public void clearCompleted() {
        boolean removed;
        synchronized (lock) {
            removed = items.removeIf(i -> i.status().terminal());
        }
        if (removed) fireChanged();
    }

    /**
     * Cancels a still-pending item. A QUEUED item is marked CANCELLED so the worker skips it; an item
     * already ACTIVE or terminal is left alone (the in-flight copy can't be interrupted mid-stream).
     * Returns true if the item was cancelled.
     */
    public boolean cancel(TransferItem item) {
        boolean changed = false;
        synchronized (lock) {
            if (item != null && item.status() == TransferStatus.QUEUED) {
                item.setStatus(TransferStatus.CANCELLED);
                changed = true;
            }
        }
        if (changed) finish(item);
        return changed;
    }

    /** Re-queues a single FAILED/CANCELLED item; no-op for DONE/SKIPPED/QUEUED/ACTIVE. */
    public boolean retry(TransferItem item) {
        boolean changed = false;
        synchronized (lock) {
            if (item != null && item.status().retryable()) {
                item.resetForRetry();
                changed = true;
                lock.notifyAll();
            }
        }
        if (changed) fireChanged();
        return changed;
    }

    /**
     * Moves a still-QUEUED item one slot earlier ({@code delta < 0}) or later ({@code delta > 0}) in
     * the queue, so the user can reprioritise pending transfers. Only reorders relative to other
     * QUEUED items; ACTIVE/terminal items keep their place and are never displaced. Returns true if
     * the item actually moved.
     */
    public boolean move(TransferItem item, int delta) {
        if (item == null || delta == 0) return false;
        boolean moved = false;
        synchronized (lock) {
            int from = items.indexOf(item);
            if (from < 0 || item.status() != TransferStatus.QUEUED) return false;
            int step = Integer.signum(delta);
            int remaining = Math.abs(delta);
            int to = from;
            while (remaining > 0) {
                int next = to + step;
                // Stop at the ends or at a non-QUEUED item (never displace active/terminal entries).
                if (next < 0 || next >= items.size()
                        || items.get(next).status() != TransferStatus.QUEUED) break;
                to = next;
                remaining--;
            }
            if (to != from) {
                items.add(to, items.remove(from));
                moved = true;
            }
        }
        if (moved) fireChanged();
        return moved;
    }

    /** Re-queues every FAILED/CANCELLED item; returns how many were reset. */
    public int retryAllFailed() {
        int n = 0;
        synchronized (lock) {
            for (TransferItem i : items) {
                if (i.status().retryable()) { i.resetForRetry(); n++; }
            }
            if (n > 0) lock.notifyAll();
        }
        if (n > 0) fireChanged();
        return n;
    }

    // ---- accounting ----

    public int count(TransferStatus status) {
        synchronized (lock) {
            return (int) items.stream().filter(i -> i.status() == status).count();
        }
    }

    public int size() { synchronized (lock) { return items.size(); } }

    /** True while any item is still queued or active. */
    public boolean hasPending() {
        synchronized (lock) {
            return items.stream().anyMatch(i -> !i.status().terminal());
        }
    }

    /** Overall progress in {@code [0,1]} across every item (terminal items count as complete). */
    public double overallProgress() {
        synchronized (lock) {
            if (items.isEmpty()) return 0;
            long done = 0, total = 0;
            for (TransferItem i : items) { done += i.accountedBytes(); total += i.weight(); }
            return total == 0 ? 0 : Math.min(1.0, (double) done / total);
        }
    }

    /** Combined throughput of all currently-active items in bytes/second (0 when none are active). */
    public double activeBytesPerSecond(long nowNanos) {
        synchronized (lock) {
            double sum = 0;
            for (TransferItem i : items) {
                if (i.status() == TransferStatus.ACTIVE) sum += i.bytesPerSecond(nowNanos);
            }
            return sum;
        }
    }

    // ---- processing ----

    /**
     * Processes every currently-queued item on the calling thread and returns when none remain
     * QUEUED. Intended for headless tests (no JavaFX, no worker thread).
     */
    public void runPending() {
        drain();
    }

    /** Starts a daemon worker that drains the queue as items arrive. Idempotent. */
    public synchronized void startWorker() {
        if (workerRunning) return;
        workerRunning = true;
        worker = new Thread(this::workerLoop, "transfer-queue");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops the background worker (already-running transfers complete first). */
    public synchronized void stopWorker() {
        workerRunning = false;
        governor.resume();   // release any paused in-flight transfer so it can wind down
        synchronized (lock) { lock.notifyAll(); }
    }

    // ---- pause / resume + bandwidth throttle (applied to the in-flight transfer) ----

    /** Pauses the queue: the active transfer blocks between chunks and no new item starts. */
    public void pause() { governor.pause(); }

    /** Resumes a paused queue. */
    public void resume() { governor.resume(); }

    public boolean isPaused() { return governor.isPaused(); }

    /** Caps overall throughput to {@code bytesPerSecond} ({@code <= 0} = unlimited). */
    public void setMaxBytesPerSecond(long bytesPerSecond) { governor.setMaxBytesPerSecond(bytesPerSecond); }

    public long maxBytesPerSecond() { return governor.maxBytesPerSecond(); }

    /**
     * When enabled, each completed file is verified with {@link TransferIntegrity} by comparing the
     * destination's byte count against the source size; a mismatch (or a missing destination) marks the
     * item FAILED so it can be retried. Off by default since it lists the destination directory per file.
     */
    public void setVerifyIntegrity(boolean verify) { this.verifyIntegrity = verify; }

    public boolean isVerifyIntegrity() { return verifyIntegrity; }

    private void workerLoop() {
        while (workerRunning) {
            drain();
            synchronized (lock) {
                if (!workerRunning) return;
                if (nextQueued() == null) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
            }
        }
    }

    /** Processes queued items one at a time until none are left QUEUED. */
    private void drain() {
        while (true) {
            TransferItem next;
            synchronized (lock) { next = nextQueued(); }
            if (next == null) return;
            process(next);
        }
    }

    private TransferItem nextQueued() {
        for (TransferItem i : items) if (i.status() == TransferStatus.QUEUED) return i;
        return null;
    }

    private void process(TransferItem item) {
        item.setStatus(TransferStatus.ACTIVE);
        item.markStarted(System.nanoTime());
        fireChanged();
        try {
            if (targetExists(item)) {
                OverwriteResolver.Action action = item.resolver().resolve(item.name());
                if (action == OverwriteResolver.Action.SKIP) {
                    item.setStatus(TransferStatus.SKIPPED);
                    item.markFinished(System.nanoTime());
                    finish(item);
                    return;
                }
            }
            execute(item);
            item.setTransferredBytes(item.totalBytes());
            if (verifyIntegrity) {
                TransferIntegrity.Report report = verifyTransfer(item);
                if (!report.verified()) {
                    item.setError("integrity check failed: " + describe(report));
                    item.setStatus(TransferStatus.FAILED);
                    item.markFinished(System.nanoTime());
                    finish(item);
                    return;
                }
            }
            item.setStatus(TransferStatus.DONE);
        } catch (Exception e) {
            item.setError(e.getMessage() == null ? e.toString() : e.getMessage());
            item.setStatus(TransferStatus.FAILED);
        }
        item.markFinished(System.nanoTime());
        finish(item);
    }

    private boolean targetExists(TransferItem item) throws Exception {
        return item.direction() == TransferItem.Direction.UPLOAD
                ? remote.exists(item.destDir(), item.name())
                : local.exists(item.destDir(), item.name());
    }

    private void execute(TransferItem item) throws Exception {
        governor.startTransfer();
        if (item.direction() == TransferItem.Direction.UPLOAD) {
            transfer.upload(Path.of(item.source().path()), item.destDir(), sent -> {
                governor.tick(sent);
                item.setTransferredBytes(sent);
                fireProgress(item);
            });
        } else {
            transfer.download(item.source(), Path.of(item.destDir()), read -> {
                governor.tick(read);
                item.setTransferredBytes(read);
                fireProgress(item);
            });
        }
    }

    /**
     * Verifies a just-completed transfer by comparing the destination file's byte count against the
     * source size ({@link TransferIntegrity}). The destination is on the opposite side to the source;
     * its size is read by listing the destination directory (the {@link FileSystem} has no cheaper stat).
     */
    private TransferIntegrity.Report verifyTransfer(TransferItem item) throws Exception {
        FileSystem destFs = item.direction() == TransferItem.Direction.UPLOAD ? remote : local;
        Long actual = destSize(destFs, item.destDir(), item.name());
        return TransferIntegrity.verify(item.totalBytes(), actual);
    }

    /** The size of {@code name} inside {@code dir}, or null when it is not present. */
    private Long destSize(FileSystem fs, String dir, String name) throws Exception {
        for (FileItem f : fs.list(dir)) {
            if (!f.parent() && f.name().equals(name)) return f.size();
        }
        return null;
    }

    /** A short human summary of a failed integrity report for the item's error line. */
    private static String describe(TransferIntegrity.Report r) {
        if (r.issues().contains(TransferIntegrity.Issue.DESTINATION_MISSING)) return "destination file missing";
        if (r.issues().contains(TransferIntegrity.Issue.SIZE_MISMATCH)) {
            return "size " + r.actualSize() + " ≠ expected " + r.expectedSize();
        }
        return r.issues().toString();
    }

    private void finish(TransferItem item) {
        fireChanged();
        try { onItemFinished.accept(item); } catch (RuntimeException ignored) { }
    }

    private void fireChanged() {
        for (Listener l : listeners) l.onQueueChanged();
    }

    private void fireProgress(TransferItem item) {
        for (Listener l : listeners) l.onItemProgress(item);
    }
}
