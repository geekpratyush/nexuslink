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
    private volatile boolean workerRunning;
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

    /** Removes all terminal (DONE/SKIPPED/FAILED) items. */
    public void clearCompleted() {
        boolean removed;
        synchronized (lock) {
            removed = items.removeIf(i -> i.status().terminal());
        }
        if (removed) fireChanged();
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
        synchronized (lock) { lock.notifyAll(); }
    }

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
        fireChanged();
        try {
            if (targetExists(item)) {
                OverwriteResolver.Action action = item.resolver().resolve(item.name());
                if (action == OverwriteResolver.Action.SKIP) {
                    item.setStatus(TransferStatus.SKIPPED);
                    finish(item);
                    return;
                }
            }
            execute(item);
            item.setTransferredBytes(item.totalBytes());
            item.setStatus(TransferStatus.DONE);
        } catch (Exception e) {
            item.setError(e.getMessage() == null ? e.toString() : e.getMessage());
            item.setStatus(TransferStatus.FAILED);
        }
        finish(item);
    }

    private boolean targetExists(TransferItem item) throws Exception {
        return item.direction() == TransferItem.Direction.UPLOAD
                ? remote.exists(item.destDir(), item.name())
                : local.exists(item.destDir(), item.name());
    }

    private void execute(TransferItem item) throws Exception {
        if (item.direction() == TransferItem.Direction.UPLOAD) {
            transfer.upload(Path.of(item.source().path()), item.destDir(), sent -> {
                item.setTransferredBytes(sent);
                fireProgress(item);
            });
        } else {
            transfer.download(item.source(), Path.of(item.destDir()), read -> {
                item.setTransferredBytes(read);
                fireProgress(item);
            });
        }
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
