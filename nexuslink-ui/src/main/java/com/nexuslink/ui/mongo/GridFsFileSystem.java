package com.nexuslink.ui.mongo;

import com.nexuslink.protocol.mongo.MongoService;
import com.nexuslink.ui.files.FileItem;
import com.nexuslink.ui.files.FileSystem;
import com.nexuslink.ui.files.FileTransfer;
import com.nexuslink.ui.files.ObjectPath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Adapts MongoDB's GridFS to the generic {@link FileSystem}/{@link FileTransfer} contracts, so a
 * database's file buckets drive one pane of the two-pane commander — the same WinSCP-style browser
 * (transfer queue, drag-and-drop, quick-view, checksums) already used for SFTP, FTP, S3, Azure and
 * GCS.
 *
 * <p>Compass has no file manager at all: GridFS is visible there only as the raw {@code .files} and
 * {@code .chunks} collections, which is unusable for actually getting a file in or out. Reusing the
 * commander seam is what makes this a few dozen lines rather than a new feature.
 *
 * <p>The path shape is {@code /bucket/filename}: the root lists buckets, a bucket lists its files.
 * GridFS filenames are flat — a {@code /} in a name is part of the name, not a directory — so there
 * are no nested folders inside a bucket, and {@link #mkdir} creates a bucket only at the root.
 */
public final class GridFsFileSystem implements FileSystem, FileTransfer {

    /** Quick-view and in-place edit read at most this much of a file into memory. */
    private static final long MAX_IN_MEMORY = 16L * 1024 * 1024;

    private final MongoService service;

    public GridFsFileSystem(MongoService service) { this.service = service; }

    @Override public String name() { return "GridFS"; }

    @Override public String home() { return "/"; }

    @Override public String parent(String path) { return ObjectPath.parent(path); }

    @Override public String join(String dir, String name) { return ObjectPath.join(dir, name, false); }

    @Override
    public List<FileItem> list(String path) {
        List<FileItem> out = new ArrayList<>();
        if (ObjectPath.isRoot(path)) {
            for (String bucket : service.gridFsBuckets()) {
                out.add(FileItem.of(bucket, "/" + bucket, true, 0, "", "bucket"));
            }
            return out;
        }
        String bucket = ObjectPath.bucket(path);
        for (MongoService.GridFsEntry entry : service.gridFsList(bucket)) {
            out.add(FileItem.of(entry.filename(), "/" + bucket + "/" + entry.filename(),
                    false, entry.length(),
                    entry.uploadDate() == null ? "" : entry.uploadDate().toInstant().toString(),
                    "", entry.uploadDate() == null ? 0 : entry.uploadDate().getTime()));
        }
        return out;
    }

    /**
     * Creating a "directory" means creating a bucket, and a GridFS bucket only exists once it holds a
     * file — so a new bucket is seeded with an empty {@code .keep} entry rather than silently doing
     * nothing.
     */
    @Override
    public void mkdir(String path) {
        String bucket = ObjectPath.bucket(path);
        String rest = ObjectPath.prefix(path);
        if (rest != null && !rest.isBlank()) {
            throw new UnsupportedOperationException(
                    "GridFS filenames are flat — a bucket has no sub-folders");
        }
        service.gridFsWrite(bucket, ".keep", new byte[0]);
    }

    @Override
    public void rename(String from, String to) {
        String bucket = ObjectPath.bucket(from);
        if (!bucket.equals(ObjectPath.bucket(to))) {
            throw new UnsupportedOperationException("a GridFS file cannot move between buckets");
        }
        service.gridFsRename(bucket, ObjectPath.prefix(from), ObjectPath.prefix(to));
    }

    @Override
    public void delete(FileItem item) {
        String bucket = ObjectPath.bucket(item.path());
        if (item.directory()) {
            service.gridFsDropBucket(bucket);   // deleting a "folder" drops the whole bucket
            return;
        }
        service.gridFsDelete(bucket, ObjectPath.prefix(item.path()));
    }

    @Override public boolean supportsContentAccess() { return true; }

    @Override
    public byte[] readFile(FileItem item, long maxBytes) throws Exception {
        return service.gridFsRead(ObjectPath.bucket(item.path()), ObjectPath.prefix(item.path()),
                Math.min(maxBytes, MAX_IN_MEMORY));
    }

    @Override
    public void writeFile(String dir, String name, byte[] data) {
        String bucket = ObjectPath.bucket(ObjectPath.join(dir, name, false));
        // GridFS keeps revisions rather than overwriting; replace so the browser behaves like a disk.
        service.gridFsDelete(bucket, name);
        service.gridFsWrite(bucket, name, data);
    }

    @Override
    public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
        upload(localFile, remoteDir, localFile.getFileName().toString(), progress);
    }

    @Override
    public void upload(Path localFile, String remoteDir, String destName, LongConsumer progress)
            throws Exception {
        String bucket = ObjectPath.bucket(remoteDir);
        if (bucket == null) throw new IllegalArgumentException("Choose a bucket to upload into");
        service.gridFsDelete(bucket, destName);
        service.gridFsUpload(bucket, destName, localFile, progress);
    }

    @Override
    public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
        download(remoteFile, localDir, remoteFile.name(), progress);
    }

    @Override
    public void download(FileItem remoteFile, Path localDir, String destName, LongConsumer progress)
            throws Exception {
        service.gridFsDownload(ObjectPath.bucket(remoteFile.path()),
                ObjectPath.prefix(remoteFile.path()), localDir.resolve(destName), progress);
    }
}
