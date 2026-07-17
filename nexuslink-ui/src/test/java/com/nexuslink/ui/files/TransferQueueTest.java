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

    /** Minimal {@link FileTransfer} that copies between two local directories (honouring destName). */
    private static final class CopyTransfer implements FileTransfer {
        @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
            upload(localFile, remoteDir, localFile.getFileName().toString(), progress);
        }
        @Override public void upload(Path localFile, String remoteDir, String destName, LongConsumer progress) throws Exception {
            copy(localFile, Path.of(remoteDir).resolve(destName), progress);
        }
        @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
            download(remoteFile, localDir, remoteFile.name(), progress);
        }
        @Override public void download(FileItem remoteFile, Path localDir, String destName, LongConsumer progress) throws Exception {
            copy(Path.of(remoteFile.path()), localDir.resolve(destName), progress);
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

    /**
     * A {@link FileTransfer} that lands the right <em>number</em> of bytes but the wrong ones — the exact
     * corruption a size-only integrity check cannot see, and the reason the digest check exists.
     */
    private static final class CorruptingTransfer implements FileTransfer {
        @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
            corruptCopy(localFile, Path.of(remoteDir).resolve(localFile.getFileName()), progress);
        }
        @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
            Path src = Path.of(remoteFile.path());
            corruptCopy(src, localDir.resolve(src.getFileName()), progress);
        }
        private void corruptCopy(Path src, Path dst, LongConsumer progress) throws Exception {
            byte[] all = Files.readAllBytes(src);
            if (all.length > 0) all[all.length / 2] ^= 0xFF;   // flip one byte, preserve the length
            Files.write(dst, all);
            progress.accept(all.length);
        }
    }

    /** The local disk, but declining to hash — stands in for a remote service that cannot report a digest. */
    private static class NoChecksumFileSystem implements FileSystem {
        private final LocalFileSystem delegate = new LocalFileSystem();
        @Override public String name() { return "NoHash"; }
        @Override public String home() throws Exception { return delegate.home(); }
        @Override public String parent(String path) { return delegate.parent(path); }
        @Override public String join(String dir, String name) { return delegate.join(dir, name); }
        @Override public List<FileItem> list(String path) throws Exception { return delegate.list(path); }
        @Override public void mkdir(String path) throws Exception { delegate.mkdir(path); }
        @Override public void rename(String from, String to) throws Exception { delegate.rename(from, to); }
        @Override public void delete(FileItem item) throws Exception { delegate.delete(item); }
        @Override public boolean supportsChecksum() { return false; }
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
    void checksumVerifyIsOffByDefaultAndTracksItsSetter() {
        TransferQueue q = queue(null, null);
        assertFalse(q.isVerifyChecksum());
        q.setVerifyChecksum(true);
        assertTrue(q.isVerifyChecksum());
    }

    @Test
    void checksumVerifyPassesForAFaithfulCopy(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = queue(local, remote);
        q.setVerifyIntegrity(true);
        q.setVerifyChecksum(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
        assertNull(created.get(0).error());
    }

    @Test
    void checksumVerifyCatchesCorruptionThatPreservesSize(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new CorruptingTransfer());
        q.setVerifyIntegrity(true);
        q.setVerifyChecksum(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        TransferItem item = created.get(0);
        assertEquals(TransferStatus.FAILED, item.status());
        assertNotNull(item.error());
        assertTrue(item.error().contains("integrity"), item.error());
        assertTrue(item.error().contains(Checksum.ALGORITHM), item.error());
        assertTrue(item.status().retryable());
    }

    @Test
    void sizeOnlyVerifyMissesCorruptionThatPreservesSize(@TempDir Path local, @TempDir Path remote) throws Exception {
        // The gap the digest check closes: same length, different bytes, and Verify alone is happy.
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new CorruptingTransfer());
        q.setVerifyIntegrity(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
    }

    @Test
    void checksumVerifyIsIgnoredWhileIntegrityVerifyIsOff(@TempDir Path local, @TempDir Path remote) throws Exception {
        // Hashing rides on top of Verify; with Verify off nothing is checked at all.
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new CorruptingTransfer());
        q.setVerifyChecksum(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
    }

    @Test
    void checksumVerifyFallsBackToSizeWhenASideCannotHash(@TempDir Path local, @TempDir Path remote) throws Exception {
        // The destination cannot report a digest, so the corruption goes unseen — but the transfer must
        // still succeed rather than fail on an unavailable check.
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new NoChecksumFileSystem(), new CorruptingTransfer());
        q.setVerifyIntegrity(true);
        q.setVerifyChecksum(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
        assertNull(created.get(0).error());
    }

    @Test
    void checksumVerifyStillCatchesATruncatedTransferBySize(@TempDir Path local, @TempDir Path remote) throws Exception {
        // Size and digest both differ; the size issue is the one reported, being the more legible failure.
        Path src = Files.writeString(local.resolve("a.txt"), "hello world");
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new TruncatingTransfer());
        q.setVerifyIntegrity(true);
        q.setVerifyChecksum(true);

        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite());
        q.runPending();

        TransferItem item = created.get(0);
        assertEquals(TransferStatus.FAILED, item.status());
        assertTrue(item.error().contains("size"), item.error());
    }

    @Test
    void localFileSystemHashesFileContent(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("a.txt"), "abc");
        LocalFileSystem fs = new LocalFileSystem();

        assertTrue(fs.supportsChecksum());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                fs.checksum(fileItemFor(f)));
    }

    @Test
    void inMemoryFileSystemDeclinesToHashAFileTooBigToBuffer() {
        // Regression guard: readFile clamps at Integer.MAX_VALUE, so hashing a larger file through the
        // default would digest a truncated prefix and report a mismatch on a perfectly good transfer.
        // Such a file must report "cannot hash" (→ size-only fallback) rather than produce a wrong digest.
        FileSystem inMemory = new NoChecksumFileSystem() {
            @Override public boolean supportsContentAccess() { return true; }
            @Override public boolean supportsChecksum() { return true; }
        };
        FileItem huge = FileItem.of("big.iso", "/tmp/big.iso", false,
                FileSystem.MAX_IN_MEMORY_CHECKSUM_BYTES + 1, "", "");
        FileItem small = FileItem.of("a.txt", "/tmp/a.txt", false, 11, "", "");

        assertFalse(inMemory.canChecksum(huge));
        assertTrue(inMemory.canChecksum(small));
        // ...and it refuses outright rather than hashing a truncated read, if called anyway.
        assertThrows(UnsupportedOperationException.class, () -> inMemory.checksum(huge));
    }

    @Test
    void localFileSystemHashesAFileOfAnySizeBecauseItStreams() {
        // The streaming override has no such ceiling — no truncation, so no false mismatch.
        FileItem huge = FileItem.of("big.iso", "/tmp/big.iso", false,
                FileSystem.MAX_IN_MEMORY_CHECKSUM_BYTES + 1, "", "");
        assertTrue(new LocalFileSystem().canChecksum(huge));
    }

    @Test
    void localFileSystemRefusesToHashADirectory(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("sub"));
        LocalFileSystem fs = new LocalFileSystem();
        FileItem sub = fs.list(dir.toString()).stream().filter(FileItem::directory).findFirst().orElseThrow();

        assertThrows(UnsupportedOperationException.class, () -> fs.checksum(sub));
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
    void renameOnConflictKeepsBothUnderASuffixedName(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "new");
        Files.writeString(remote.resolve("a.txt"), "old");          // conflict
        TransferQueue q = queue(local, remote);

        OverwriteResolver rename = new OverwriteResolver(name -> OverwriteResolver.Choice.RENAME);
        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), rename);
        q.runPending();

        assertEquals(TransferStatus.DONE, created.get(0).status());
        assertEquals("old", Files.readString(remote.resolve("a.txt")), "existing file is untouched");
        assertEquals("new", Files.readString(remote.resolve("a copy.txt")), "incoming lands under a new name");
        assertEquals("a copy.txt", created.get(0).destName());
    }

    @Test
    void overwriteIfNewerSkipsAnOlderSource(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "src");
        Path dst = Files.writeString(remote.resolve("a.txt"), "dst");
        // Make the destination strictly newer than the source.
        Files.setLastModifiedTime(src, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(dst, java.nio.file.attribute.FileTime.fromMillis(2_000_000));
        TransferQueue q = queue(local, remote);

        OverwriteResolver ifNewer = new OverwriteResolver(name -> OverwriteResolver.Choice.OVERWRITE_IF_NEWER);
        List<TransferItem> created = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), ifNewer);
        q.runPending();

        assertEquals(TransferStatus.SKIPPED, created.get(0).status());
        assertEquals("dst", Files.readString(remote.resolve("a.txt")), "older source must not overwrite");
    }

    @Test
    void overwriteIfNewerReplacesAnOlderTarget(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "src");
        Path dst = Files.writeString(remote.resolve("a.txt"), "dst");
        Files.setLastModifiedTime(src, java.nio.file.attribute.FileTime.fromMillis(2_000_000));
        Files.setLastModifiedTime(dst, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        TransferQueue q = queue(local, remote);

        OverwriteResolver ifNewer = new OverwriteResolver(name -> OverwriteResolver.Choice.OVERWRITE_IF_NEWER);
        q.enqueue(TransferItem.Direction.UPLOAD, List.of(fileItemFor(src)), remote.toString(), ifNewer);
        q.runPending();

        assertEquals("src", Files.readString(remote.resolve("a.txt")), "newer source overwrites");
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

    // ---- auto-retry on transient errors ----

    /** A {@link FileTransfer} that throws a chosen error for its first {@code failures} attempts, then copies. */
    private static final class FlakyTransfer implements FileTransfer {
        private final int failures;
        private final Exception error;
        private int attempts;
        FlakyTransfer(int failures, Exception error) { this.failures = failures; this.error = error; }
        int attempts() { return attempts; }
        @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
            run(localFile, Path.of(remoteDir).resolve(localFile.getFileName()), progress);
        }
        @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
            Path src = Path.of(remoteFile.path());
            run(src, localDir.resolve(src.getFileName()), progress);
        }
        private void run(Path src, Path dst, LongConsumer progress) throws Exception {
            if (attempts++ < failures) throw error;
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(Files.size(src));
        }
    }

    private static TransferQueue flakyQueue(FlakyTransfer transfer, RetryPolicy policy) {
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), transfer);
        q.setAutoRetry(policy);
        q.setBackoffSleeper(ms -> {});   // no real waiting in tests
        return q;
    }

    @Test
    void transientFailureIsAutoRetriedToSuccess(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "payload");
        FlakyTransfer transfer = new FlakyTransfer(2, new java.net.SocketTimeoutException("read timed out"));
        TransferQueue q = flakyQueue(transfer, RetryPolicy.defaultPolicy());   // 3 attempts

        TransferItem item = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        q.runPending();

        assertEquals(TransferStatus.DONE, item.status());
        assertEquals(3, item.attempts(), "two failures + one success");
        assertTrue(Files.exists(remote.resolve("a.txt")));
    }

    @Test
    void transientFailureFailsAfterExhaustingAttempts(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "payload");
        FlakyTransfer transfer = new FlakyTransfer(99, new java.net.ConnectException("connection refused"));
        TransferQueue q = flakyQueue(transfer, new RetryPolicy(3, 0, 1, 0));

        TransferItem item = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        q.runPending();

        assertEquals(TransferStatus.FAILED, item.status());
        assertEquals(3, item.attempts(), "capped at maxAttempts");
    }

    @Test
    void permanentErrorIsNotRetried(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "payload");
        FlakyTransfer transfer = new FlakyTransfer(99, new java.nio.file.AccessDeniedException("permission denied"));
        TransferQueue q = flakyQueue(transfer, RetryPolicy.defaultPolicy());

        TransferItem item = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        q.runPending();

        assertEquals(TransferStatus.FAILED, item.status());
        assertEquals(1, item.attempts(), "a permanent error is not retried");
    }

    @Test
    void autoRetryOffLeavesTransientFailureFailedOnFirstTry(@TempDir Path local, @TempDir Path remote) throws Exception {
        Path src = Files.writeString(local.resolve("a.txt"), "payload");
        FlakyTransfer transfer = new FlakyTransfer(99, new java.net.SocketTimeoutException("timed out"));
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), transfer);
        // no setAutoRetry → default RetryPolicy.none()

        TransferItem item = q.enqueue(TransferItem.Direction.UPLOAD,
                List.of(fileItemFor(src)), remote.toString(), OverwriteResolver.alwaysOverwrite()).get(0);
        q.runPending();

        assertEquals(TransferStatus.FAILED, item.status());
        assertEquals(1, item.attempts());
    }

    @Test
    void concurrencyDefaultsToOne() {
        assertEquals(1, new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new CopyTransfer()).concurrency());
    }

    @Test
    void setConcurrencyClampsToAtLeastOne() {
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), new CopyTransfer());
        q.setConcurrency(4);
        assertEquals(4, q.concurrency());
        q.setConcurrency(0);
        assertEquals(1, q.concurrency());
        q.setConcurrency(-3);
        assertEquals(1, q.concurrency());
    }

    /**
     * Proves the workers actually run in parallel: each transfer waits on a barrier that only trips
     * once N of them are simultaneously in flight. With concurrency N all N arrive and the barrier
     * releases; with fewer workers the barrier would time out and the transfers would fail. So all-DONE
     * is a deterministic witness of true concurrency (no reliance on sleep timing).
     */
    @Test
    void parallelWorkersRunTransfersConcurrently(@TempDir Path local, @TempDir Path remote) throws Exception {
        int n = 4;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(n);
        FileTransfer barriered = new FileTransfer() {
            @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
                try {
                    barrier.await(3, java.util.concurrent.TimeUnit.SECONDS);   // only trips when n are active together
                } catch (Exception e) {
                    throw new Exception("transfers did not run concurrently", e);
                }
                Files.copy(localFile, Path.of(remoteDir).resolve(localFile.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
                progress.accept(Files.size(localFile));
            }
            @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) {}
        };
        TransferQueue q = new TransferQueue(new LocalFileSystem(), new LocalFileSystem(), barriered);
        q.setConcurrency(n);

        java.util.List<FileItem> sources = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) sources.add(fileItemFor(Files.writeString(local.resolve("f" + i + ".txt"), "x")));
        q.enqueue(TransferItem.Direction.UPLOAD, sources, remote.toString(), OverwriteResolver.alwaysOverwrite());

        q.startWorker();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (q.hasPending() && System.nanoTime() < deadline) Thread.sleep(20);
        q.stopWorker();

        assertEquals(n, q.count(TransferStatus.DONE), "all files should transfer in parallel and complete");
        for (int i = 0; i < n; i++) assertTrue(Files.exists(remote.resolve("f" + i + ".txt")));
    }
}
