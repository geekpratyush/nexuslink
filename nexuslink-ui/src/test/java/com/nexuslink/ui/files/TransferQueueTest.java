package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless tests for the transfer-queue model: state transitions, progress accounting and
 * overwrite-policy handling. No JavaFX toolkit is required — a {@link LocalFileSystem} backs both
 * sides and {@link TransferQueue#runPending()} runs synchronously on the test thread.
 */
class TransferQueueTest {

    /** Minimal {@link FileTransfer} that copies between two local directories. */
    private static final class CopyTransfer implements FileTransfer {
        @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
            copy(localFile, Path.of(remoteDir).resolve(localFile.getFileName()), progress);
        }
        @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
            Path src = Path.of(remoteFile.path());
            copy(src, localDir.resolve(src.getFileName()), progress);
        }
        private void copy(Path src, Path dst, LongConsumer progress) throws Exception {
            long size = Files.size(src);
            progress.accept(0);
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(size);
        }
    }

    /** A {@link FileTransfer} that writes a truncated destination, to exercise integrity failure. */
    private static final class TruncatingTransfer implements FileTransfer {
        @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
            truncateCopy(localFile, Path.of(remoteDir).resolve(localFile.getFileName()), progress);
        }
        @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
            Path src = Path.of(remoteFile.path());
            truncateCopy(src, localDir.resolve(src.getFileName()), progress);
        }
        private void truncateCopy(Path src, Path dst, LongConsumer progress) throws Exception {
            byte[] all = Files.readAllBytes(src);
            Files.write(dst, java.util.Arrays.copyOf(all, Math.max(0, all.length - 1)));   // drop one byte
            progress.accept(all.length);
        }
    }

    private static TransferQueue queue(Path localDir, Path remoteDir) {
        return new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new CopyTransfer());
    }

    private static FileItem fileItemFor(Path file) throws Exception {
        return new LocalFileSystem().list(file.getParent().toString()).stream()
                .filter(f -> f.name().equals(file.getFileName().toString()))
                .findFirst().orElseThrow();
    }

    @Test
    void pauseAndThrottleControlsDelegateToGovernor() {
        TransferQueue q = queue(null, null);
        assertFalse(q.isPaused());
        q.pause();
        assertTrue(q.isPaused());
        q.resume();
        assertFalse(q.isPaused());

        assertEquals(0, q.maxBytesPerSecond());       // unlimited by default
        q.setMaxBytesPerSecond(1_048_576);
        assertEquals(1_048_576, q.maxBytesPerSecond());
        q.setMaxBytesPerSecond(-5);                   // clamped to unlimited
        assertEquals(0, q.maxBytesPerSecond());
    }

    @Test
    void uploadMovesThroughQueuedActiveDone(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = queue(local, remote);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        assertEquals(1, created.size());
        TransferItem item = created.get(0);

        assertEquals(TransferStatus.QUEUED, item.status());
        assertEquals(0.0, q.overallProgress());
        assertTrue(q.hasPending());

        q.runPending();

        assertEquals(TransferStatus.DONE, item.status());
        assertEquals(item.totalBytes(), item.transferredBytes());
        assertEquals(1.0, item.progress());
        assertEquals(1.0, q.overallProgress(), 1e-9);
        assertFalse(q.hasPending());
        assertEquals("hello world", Files.readString(remote.resolve("a.txt")));
        assertEquals(1, q.count(TransferStatus.DONE));
    }

    @Test
    void integrityVerifyPassesForAFaithfulCopy(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = queue(local, remote);
        q.setVerifyIntegrity(true);
        assertTrue(q.isVerifyIntegrity());

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
        assertNull(created.get(0).error());
    }

    @Test
    void integrityVerifyFailsAndMarksTruncatedTransferFailed(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new TruncatingTransfer());
        q.setVerifyIntegrity(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        TransferItem item = created.get(0);
        assertEquals(TransferStatus.FAILED, item.status());
        assertNotNull(item.error());
        assertTrue(item.error().contains("integrity"), item.error());
        // Failed items are retryable, so the user can re-run the transfer.
        assertTrue(item.status().retryable());
    }

    @Test
    void integrityVerifyOffLeavesTruncatedTransferDone(@TempDir Path local, @TempDir Path remote) throws Exception {
        // Without verification the queue trusts the copy and reports DONE even if bytes were lost.
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new TruncatingTransfer());

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
    }

    @Test
    void uploadMoveDeletesSourceAfterCopy(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "move me");
        TransferQueue q = queue(local, remote);
        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite(), true);
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
        assertEquals("move me", Files.readString(remote.resolve("a.txt")), "copied to destination");
        assertFalse(Files.exists(src), "source removed after a successful move");
    }

    @Test
    void downloadMoveDeletesRemoteSource(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(remote.resolve("data.bin"), "payload");
        TransferQueue q = queue(local, remote);
        q.enqueue(TransferItem.Direction.DOWNLOAD,
                List.of(fileItemFor(src)), local.toString(), OverwriteResolver.alwaysOverwrite(), true);
        q.runPending();

        assertEquals("payload", Files.readString(local.resolve("data.bin")));
        assertFalse(Files.exists(src), "remote source removed after a successful download-move");
    }

    @Test
    void skippedMoveLeavesSourceIntact(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "keep");
        Files.writeString(remote.resolve("a.txt"), "existing");   // target exists → resolver will skip
        TransferQueue q = queue(local, remote);
        q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(src)), remote.toString(),
                new OverwriteResolver(name -> OverwriteResolver.Choice.SKIP), true);
        q.runPending();

        assertTrue(Files.exists(src), "a skipped move must not delete the source");
        assertEquals("existing", Files.readString(remote.resolve("a.txt")), "destination untouched");
    }

    @Test
    void downloadCopiesRemoteToLocal(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(remote.resolve("data.bin"), "payload");
        TransferQueue q = queue(local, remote);

        q.enqueue(TransferItem.Direction.DOWNLOAD,
                List.of(fileItemFor(src)), local.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals("payload", Files.readString(local.resolve("data.bin")));
        assertEquals(1, q.count(TransferStatus.DONE));
    }

    @Test
    void existingTargetIsSkippedWhenPolicySkips(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "new");
        Files.writeString(remote.resolve("a.txt"), "old");          // conflict
        TransferQueue q = queue(local, remote);

        OverwriteResolver skip = new OverwriteResolver(name -> OverwriteResolver.Choice.SKIP);
        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), skip);
        q.runPending();

        assertEquals(TransferStatus.SKIPPED, created.get(0).status());
        assertEquals("old", Files.readString(remote.resolve("a.txt")), "skip must keep the original");
        assertEquals(1, q.count(TransferStatus.SKIPPED));
    }

    @Test
    void existingTargetIsReplacedWhenPolicyOverwrites(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "new");
        Files.writeString(remote.resolve("a.txt"), "old");
        TransferQueue q = queue(local, remote);

        OverwriteResolver overwrite = new OverwriteResolver(name -> OverwriteResolver.Choice.OVERWRITE);
        q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), overwrite);
        q.runPending();

        assertEquals("new", Files.readString(remote.resolve("a.txt")));
    }

    @Test
    void overwriteAllAppliesToTheRestOfTheBatch(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path a = Files.writeString(local.resolve("a.txt"), "A2");
        Path b = Files.writeString(local.resolve("b.txt"), "B2");
        Files.writeString(remote.resolve("a.txt"), "A1");
        Files.writeString(remote.resolve("b.txt"), "B1");
        TransferQueue q = queue(local, remote);

        AtomicInteger prompts = new AtomicInteger();
        OverwriteResolver resolver = new OverwriteResolver(name -> {
            prompts.incrementAndGet();
            return OverwriteResolver.Choice.OVERWRITE_ALL;
        });
        q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(a), fileItemFor(b)), remote.toString(), resolver);
        q.runPending();

        assertEquals(1, prompts.get(), "Overwrite all should only prompt once");
        assertEquals("A2", Files.readString(remote.resolve("a.txt")));
        assertEquals("B2", Files.readString(remote.resolve("b.txt")));
        assertEquals(2, q.count(TransferStatus.DONE));
    }

    @Test
    void failedTransferIsRecordedNotThrown(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("gone.txt"), "x");
        FileItem item = fileItemFor(src);
        Files.delete(src);                                          // make the source vanish
        TransferQueue q = queue(local, remote);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(item), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.FAILED, created.get(0).status());
        assertNotNull(created.get(0).error());
        assertEquals(1, q.count(TransferStatus.FAILED));
    }

    @Test
    void overallProgressAndClearCompletedAcrossMixedBatch(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path a = Files.writeString(local.resolve("a.txt"), "aaaa");
        Path b = Files.writeString(local.resolve("b.txt"), "bbbb");
        Files.writeString(remote.resolve("b.txt"), "keep");        // b conflicts → skipped
        TransferQueue q = queue(local, remote);

        OverwriteResolver skip = new OverwriteResolver(name -> OverwriteResolver.Choice.SKIP);
        q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(a), fileItemFor(b)), remote.toString(), skip);
        q.runPending();

        assertEquals(1, q.count(TransferStatus.DONE));
        assertEquals(1, q.count(TransferStatus.SKIPPED));
        assertEquals(1.0, q.overallProgress(), 1e-9, "every item is terminal so the bar is full");

        q.clearCompleted();
        assertEquals(0, q.size());
        assertEquals(0.0, q.overallProgress());
    }

    @Test
    void enqueueIgnoresDirectoriesAndParentRows(@TempDir Path local, @TempDir Path remote) throws Exception {
        Files.createDirectory(local.resolve("sub"));
        Path src = Files.writeString(local.resolve("a.txt"), "x");
        TransferQueue q = queue(local, remote);

        FileItem dir = new LocalFileSystem().list(local.toString()).stream()
                .filter(FileItem::directory).findFirst().orElseThrow();
        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(FileItem.up(local.toString()), dir, fileItemFor(src)),
                remote.toString(), OverwriteResolver.alwaysOverwrite());

        assertEquals(1, created.size(), "directories and the .. row must be filtered out");
        assertEquals("a.txt", created.get(0).name());
    }

    @Test
    void recursiveUploadWalksTreeAndRecreatesDirectories(@TempDir Path local, @TempDir Path remote) throws Exception {
        // local/proj/{readme.txt, src/main.txt}
        Path proj = Files.createDirectory(local.resolve("proj"));
        Files.writeString(proj.resolve("readme.txt"), "top");
        Path srcDir = Files.createDirectory(proj.resolve("src"));
        Files.writeString(srcDir.resolve("main.txt"), "nested");
        TransferQueue q = queue(local, remote);

        FileItem dir = dirItemFor(local, "proj");
        List<TransferItem> created = q.enqueueRecursive(TransferItem.Direction.UPLOAD,
                List.of(dir), remote.toString(), OverwriteResolver.alwaysOverwrite());
        assertEquals(2, created.size(), "both files in the tree are enqueued");
        q.runPending();

        assertEquals("top", Files.readString(remote.resolve("proj/readme.txt")));
        assertEquals("nested", Files.readString(remote.resolve("proj/src/main.txt")));
        assertTrue(Files.isDirectory(remote.resolve("proj/src")), "nested dir recreated on the dest side");
        assertEquals(2, q.count(TransferStatus.DONE));
    }

    @Test
    void recursiveDownloadMixesFilesAndFolders(@TempDir Path local, @TempDir Path remote) throws Exception {
        Files.writeString(remote.resolve("loose.txt"), "L");
        Path d = Files.createDirectory(remote.resolve("data"));
        Files.writeString(d.resolve("a.bin"), "A");
        TransferQueue q = queue(local, remote);

        List<TransferItem> created = q.enqueueRecursive(TransferItem.Direction.DOWNLOAD,
                List.of(fileItemFor(remote.resolve("loose.txt")), dirItemFor(remote, "data")),
                local.toString(), OverwriteResolver.alwaysOverwrite());
        assertEquals(2, created.size());
        q.runPending();

        assertEquals("L", Files.readString(local.resolve("loose.txt")));
        assertEquals("A", Files.readString(local.resolve("data/a.bin")));
    }

    @Test
    void recursiveEnqueueSkipsTheParentRow(@TempDir Path local, @TempDir Path remote) throws Exception {
        Files.writeString(local.resolve("a.txt"), "x");
        TransferQueue q = queue(local, remote);

        List<TransferItem> created = q.enqueueRecursive(TransferItem.Direction.UPLOAD,
                List.of(FileItem.up(local.toString()), fileItemFor(local.resolve("a.txt"))),
                remote.toString(), OverwriteResolver.alwaysOverwrite());
        assertEquals(1, created.size(), "the .. row is ignored");
    }

    @Test
    void rateAndEtaPureHelpers() {
        // 1 MiB over 1s ⇒ 1 MiB/s; "1.0 MB/s".
        long mib = 1024L * 1024;
        assertEquals(mib, TransferItem.rate(mib, 1_000_000_000L), 1e-6);
        assertEquals("1.0 MB/s", TransferItem.formatRate(mib));
        assertEquals("", TransferItem.formatRate(0), "zero rate renders blank");

        // 10 MiB left at 1 MiB/s ⇒ 10s.
        assertEquals(10, TransferItem.etaSeconds(10 * mib, mib));
        assertEquals(-1, TransferItem.etaSeconds(mib, 0), "no rate ⇒ unknown ETA");
        assertEquals(0, TransferItem.rate(0, 0), "no time, no bytes ⇒ 0");

        assertEquals("--", TransferItem.formatEta(-1));
        assertEquals("42s", TransferItem.formatEta(42));
        assertEquals("3m07s", TransferItem.formatEta(187));
        assertEquals("1h02m", TransferItem.formatEta(3720));
    }

    @Test
    void itemTracksThroughputAndEtaWhileActive(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("big.bin"), "x".repeat(2_000_000));
        TransferItem item = new TransferItem(TransferItem.Direction.UPLOAD,
                fileItemFor(src), remote.toString(), OverwriteResolver.alwaysOverwrite());
        item.setStatus(TransferStatus.ACTIVE);
        item.markStarted(0);
        item.setTransferredBytes(1_000_000);                 // half, at t = 1s
        long oneSecond = 1_000_000_000L;

        assertEquals(1_000_000, item.bytesPerSecond(oneSecond), 1.0);
        assertEquals(1, item.etaSeconds(oneSecond), "1 MB left at 1 MB/s ⇒ 1s");

        item.markFinished(2 * oneSecond);                    // rate now frozen at the finish time
        item.setStatus(TransferStatus.DONE);
        assertEquals(0, item.etaSeconds(99 * oneSecond), "terminal ⇒ ETA 0");
        // elapsed frozen at 2s for 1 MB transferred ⇒ 0.5 MB/s regardless of 'now'.
        assertEquals(500_000, item.bytesPerSecond(99 * oneSecond), 1.0);
    }

    @Test
    void queueAggregatesActiveThroughput(@TempDir Path local, @TempDir Path remote) throws Exception {
        TransferQueue q = queue(local, remote);
        Path a = Files.writeString(local.resolve("a"), "x".repeat(1000));
        Path b = Files.writeString(local.resolve("b"), "x".repeat(1000));
        TransferItem i1 = q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(a)),
                remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        TransferItem i2 = q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(b)),
                remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        long s = 1_000_000_000L;
        for (TransferItem i : List.of(i1, i2)) {
            i.setStatus(TransferStatus.ACTIVE);
            i.markStarted(0);
            i.setTransferredBytes(500);                      // 500 B in 1s each ⇒ 500 B/s each
        }
        assertEquals(1000, q.activeBytesPerSecond(s), 1.0, "two active items sum their rates");
    }

    @Test
    void cancelledQueuedItemIsSkippedByTheWorker(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hi");
        TransferQueue q = queue(local, remote);
        TransferItem item = q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(src)),
                remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);

        assertTrue(q.cancel(item));
        assertEquals(TransferStatus.CANCELLED, item.status());
        assertTrue(item.status().terminal());
        q.runPending();
        assertFalse(Files.exists(remote.resolve("a.txt")), "cancelled item is never transferred");
        assertEquals(1, q.count(TransferStatus.CANCELLED));
    }

    @Test
    void retryRequeuesAFailedItemAndItThenSucceeds(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("gone.txt"), "x");
        FileItem item = fileItemFor(src);
        Files.delete(src);                                           // first run fails (source missing)
        TransferQueue q = queue(local, remote);
        TransferItem ti = q.enqueue(TransferItem.Direction.UPLOAD, List.of(item),
                remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        q.runPending();
        assertEquals(TransferStatus.FAILED, ti.status());

        Files.writeString(src, "back");                              // restore the source, then retry
        assertTrue(q.retry(ti));
        assertEquals(TransferStatus.QUEUED, ti.status());
        assertNull(ti.error(), "retry clears the prior error");
        q.runPending();

        assertEquals(TransferStatus.DONE, ti.status());
        assertEquals("back", Files.readString(remote.resolve("gone.txt")));
    }

    @Test
    void retryIsRejectedForDoneItemsAndRetryAllCountsOnlyFailures(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path ok = Files.writeString(local.resolve("ok.txt"), "ok");
        Path bad = Files.writeString(local.resolve("bad.txt"), "x");
        FileItem badItem = fileItemFor(bad);
        Files.delete(bad);
        TransferQueue q = queue(local, remote);
        TransferItem good = q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(ok)),
                remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        q.enqueue(TransferItem.Direction.UPLOAD, List.of(badItem),
                remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, good.status());
        assertFalse(q.retry(good), "a successful item is not retryable");
        assertEquals(1, q.retryAllFailed(), "only the FAILED item is requeued");
        assertEquals(1, q.count(TransferStatus.QUEUED));
    }

    @Test
    void moveReordersQueuedItemsAndRespectsEnds(@TempDir Path local, @TempDir Path remote) throws Exception {
        TransferQueue q = queue(local, remote);
        TransferItem a = enqueueOne(q, local, remote, "a.txt");
        TransferItem b = enqueueOne(q, local, remote, "b.txt");
        TransferItem c = enqueueOne(q, local, remote, "c.txt");
        // order: a, b, c
        assertTrue(q.move(c, -1));                       // c up → a, c, b
        assertEquals(List.of(a, c, b), q.items());
        assertTrue(q.move(c, -1));                       // c up → c, a, b
        assertEquals(List.of(c, a, b), q.items());
        assertFalse(q.move(c, -1), "already at the top");
        assertFalse(q.move(b, 1), "already at the bottom");
        assertFalse(q.move(a, 0), "zero delta is a no-op");
    }

    @Test
    void moveWontDisplaceANonQueuedItem(@TempDir Path local, @TempDir Path remote) throws Exception {
        TransferQueue q = queue(local, remote);
        TransferItem a = enqueueOne(q, local, remote, "a.txt");
        TransferItem b = enqueueOne(q, local, remote, "b.txt");
        a.setStatus(TransferStatus.ACTIVE);              // a is in flight at the top
        assertFalse(q.move(b, -1), "a queued item must not jump over an active one");
        assertEquals(List.of(a, b), q.items());
    }

    private static TransferItem enqueueOne(TransferQueue q, Path local, Path remote, String name) throws Exception {
        Path src = Files.writeString(local.resolve(name), name);
        return q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(src)),
                remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
    }

    private static FileItem dirItemFor(Path parent, String name) throws Exception {
        return new LocalFileSystem().list(parent.toString()).stream()
                .filter(f -> f.directory() && f.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void listenerReceivesQueueChanges(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hi");
        TransferQueue q = queue(local, remote);
        AtomicInteger changes = new AtomicInteger();
        q.addListener(new TransferQueue.Listener() {
            @Override public void onQueueChanged() { changes.incrementAndGet(); }
        });

        q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertTrue(changes.get() >= 2, "expected change events for enqueue + status transitions");
    }
}
