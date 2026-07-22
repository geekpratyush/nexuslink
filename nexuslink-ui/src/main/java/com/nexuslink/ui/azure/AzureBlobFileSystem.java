package com.nexuslink.ui.azure;

import com.nexuslink.protocol.azure.AzureBlobService;
import com.nexuslink.ui.files.FileItem;
import com.nexuslink.ui.files.FileSystem;
import com.nexuslink.ui.files.FileTransfer;
import com.nexuslink.ui.files.ObjectPath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Adapts {@link AzureBlobService} to the generic {@link FileSystem}/{@link FileTransfer} contracts so
 * Azure Blob Storage drives one pane of the {@link com.nexuslink.ui.files.DualPaneBrowser} — the same
 * WinSCP-style commander (transfer queue, drag-and-drop, quick-view) used for SFTP/FTP and S3.
 *
 * <p>The container list is the root; containers and virtual directories appear as folders and blobs as
 * files, with all the path math in the pure {@link ObjectPath} shared with S3/GCS. Azure has no native
 * rename, so that one operation is unsupported (copy-then-delete would be needed); everything else —
 * list, mkdir (folder marker), delete (recursive), upload, download, and in-place content read/write —
 * maps onto the service.
 */
public final class AzureBlobFileSystem implements FileSystem, FileTransfer {

    private static final int MAX_BLOBS = 1000;

    private final AzureBlobService service;

    public AzureBlobFileSystem(AzureBlobService service) { this.service = service; }

    @Override public String name() { return "Azure"; }

    @Override public String home() { return "/"; }

    @Override public String parent(String path) { return ObjectPath.parent(path); }

    @Override public String join(String dir, String name) { return ObjectPath.join(dir, name, false); }

    @Override public List<FileItem> list(String path) {
        List<FileItem> out = new ArrayList<>();
        if (ObjectPath.isRoot(path)) {
            for (String container : service.listContainers()) {
                out.add(FileItem.of(container, "/" + container, true, 0, "", "container"));
            }
            return out;
        }
        String container = ObjectPath.bucket(path);
        String prefix = ObjectPath.prefix(path);
        AzureBlobService.BlobListing listing = service.listChildren(container, prefix);
        for (String folder : listing.folders()) {   // e.g. "a/b/"
            out.add(FileItem.of(ObjectPath.lastSegment(folder), "/" + container + "/" + folder, true, 0, "", ""));
        }
        for (AzureBlobService.BlobInfo blob : listing.files()) {
            out.add(FileItem.of(ObjectPath.lastSegment(blob.name()), "/" + container + "/" + blob.name(),
                    false, blob.size(), blob.lastModified(), blob.tier()));
        }
        return out;
    }

    @Override public void mkdir(String path) {
        String container = ObjectPath.bucket(path);
        String prefix = ObjectPath.prefix(path);
        if (container == null || prefix == null || prefix.isBlank()) {
            throw new UnsupportedOperationException("Create a folder inside a container");
        }
        service.createFolder(container, prefix);
    }

    @Override public void rename(String from, String to) {
        throw new UnsupportedOperationException(
                "Azure Blob has no rename — copy the blob to the new name, then delete");
    }

    @Override public void delete(FileItem item) {
        String container = ObjectPath.bucket(item.path());
        String name = ObjectPath.prefix(item.path());
        if (item.directory()) {
            // Delete every blob under the folder's prefix (a flat listing), then the folder marker itself.
            String folderPrefix = name.endsWith("/") ? name : name + "/";
            for (AzureBlobService.BlobInfo blob : service.listBlobsUnder(container, folderPrefix, MAX_BLOBS)) {
                service.deleteBlob(container, blob.name());
            }
            service.deleteBlob(container, folderPrefix);
        } else {
            service.deleteBlob(container, name);
        }
    }

    @Override public boolean supportsContentAccess() { return true; }

    @Override public byte[] readFile(FileItem item, long maxBytes) {
        return service.getBlobBytes(ObjectPath.bucket(item.path()), ObjectPath.prefix(item.path()),
                (int) Math.min(maxBytes, Integer.MAX_VALUE));
    }

    @Override public void writeFile(String dir, String name, byte[] data) {
        service.putBlob(ObjectPath.bucket(dir), ObjectPath.childKey(dir, name), data, null);
    }

    // ---- FileTransfer ----

    @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
        upload(localFile, remoteDir, localFile.getFileName().toString(), progress);
    }

    @Override public void upload(Path localFile, String remoteDir, String destName, LongConsumer progress) throws Exception {
        String container = ObjectPath.bucket(remoteDir);
        if (container == null) throw new UnsupportedOperationException("Open a container before uploading");
        service.uploadFile(container, ObjectPath.childKey(remoteDir, destName), localFile, progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
        download(remoteFile, localDir, remoteFile.name(), progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, String destName, LongConsumer progress) throws Exception {
        service.downloadToFile(ObjectPath.bucket(remoteFile.path()), ObjectPath.prefix(remoteFile.path()),
                localDir.resolve(destName), progress);
    }
}
