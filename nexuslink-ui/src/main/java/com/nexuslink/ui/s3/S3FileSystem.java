package com.nexuslink.ui.s3;

import com.nexuslink.protocol.s3.S3Service;
import com.nexuslink.ui.files.FileItem;
import com.nexuslink.ui.files.FileSystem;
import com.nexuslink.ui.files.FileTransfer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Adapts {@link S3Service} to the generic {@link FileSystem}/{@link FileTransfer} contracts so an
 * S3-compatible object store drives one pane of the {@link com.nexuslink.ui.files.DualPaneBrowser} —
 * the same WinSCP-style commander (transfer queue, drag-and-drop, quick-view) used for SFTP/FTP.
 *
 * <p>The bucket list is the root; buckets and common prefixes appear as folders and objects as files,
 * with all the path math in the pure {@link S3Path}. S3 has no native rename, so that one operation is
 * unsupported (copy-then-delete would be needed); everything else — list, mkdir (folder marker), delete
 * (recursive), upload, download, and in-place content read/write — maps onto the service.
 */
public final class S3FileSystem implements FileSystem, FileTransfer {

    private static final int MAX_KEYS = 1000;

    private final S3Service service;

    public S3FileSystem(S3Service service) { this.service = service; }

    @Override public String name() { return "S3"; }

    @Override public String home() { return "/"; }

    @Override public String parent(String path) { return S3Path.parent(path); }

    @Override public String join(String dir, String name) { return S3Path.join(dir, name, false); }

    @Override public List<FileItem> list(String path) {
        List<FileItem> out = new ArrayList<>();
        if (S3Path.isRoot(path)) {
            for (String bucket : service.listBuckets()) {
                out.add(FileItem.of(bucket, "/" + bucket, true, 0, "", "bucket"));
            }
            return out;
        }
        String bucket = S3Path.bucket(path);
        String prefix = S3Path.prefix(path);
        S3Service.S3Listing listing = service.listChildren(bucket, prefix);
        for (String folder : listing.folders()) {   // e.g. "a/b/"
            out.add(FileItem.of(S3Path.lastSegment(folder), "/" + bucket + "/" + folder, true, 0, "", ""));
        }
        for (S3Service.S3Item item : listing.files()) {
            out.add(FileItem.of(S3Path.lastSegment(item.key()), "/" + bucket + "/" + item.key(),
                    false, item.size(), item.lastModified(), item.storageClass()));
        }
        return out;
    }

    @Override public void mkdir(String path) {
        String bucket = S3Path.bucket(path);
        String prefix = S3Path.prefix(path);
        if (bucket == null || prefix == null || prefix.isBlank()) {
            throw new UnsupportedOperationException("Create a folder inside a bucket");
        }
        service.createFolder(bucket, prefix);
    }

    @Override public void rename(String from, String to) {
        throw new UnsupportedOperationException("S3 has no rename — copy the object to the new key, then delete");
    }

    @Override public void delete(FileItem item) {
        String bucket = S3Path.bucket(item.path());
        String key = S3Path.prefix(item.path());
        if (item.directory()) {
            // Delete every object under the folder's prefix (a flat, delimiter-free listing), then the
            // folder marker itself.
            String folderPrefix = key.endsWith("/") ? key : key + "/";
            for (S3Service.S3Item obj : service.listObjects(bucket, folderPrefix, MAX_KEYS)) {
                service.deleteObject(bucket, obj.key());
            }
            service.deleteObject(bucket, folderPrefix);
        } else {
            service.deleteObject(bucket, key);
        }
    }

    @Override public boolean supportsContentAccess() { return true; }

    @Override public byte[] readFile(FileItem item, long maxBytes) {
        return service.getObjectBytes(S3Path.bucket(item.path()), S3Path.prefix(item.path()),
                (int) Math.min(maxBytes, Integer.MAX_VALUE));
    }

    @Override public void writeFile(String dir, String name, byte[] data) {
        service.putObject(S3Path.bucket(dir), S3Path.childKey(dir, name), data, null);
    }

    // ---- FileTransfer ----

    @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
        String bucket = S3Path.bucket(remoteDir);
        if (bucket == null) throw new UnsupportedOperationException("Open a bucket before uploading");
        service.uploadFile(bucket, S3Path.childKey(remoteDir, localFile.getFileName().toString()),
                localFile, progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
        service.downloadToFile(S3Path.bucket(remoteFile.path()), S3Path.prefix(remoteFile.path()),
                localDir.resolve(remoteFile.name()), progress);
    }
}
