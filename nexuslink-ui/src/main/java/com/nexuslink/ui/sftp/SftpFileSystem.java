package com.nexuslink.ui.sftp;

import com.nexuslink.protocol.sftp.SftpService;
import com.nexuslink.ui.files.FileItem;
import com.nexuslink.ui.files.FileSystem;
import com.nexuslink.ui.files.FileTransfer;

import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Adapts {@link SftpService} to the generic {@link FileSystem}/{@link FileTransfer} contracts so an
 * SFTP server can drive one side of the {@link com.nexuslink.ui.files.DualPaneBrowser}.
 */
final class SftpFileSystem implements FileSystem, FileTransfer {

    private final SftpService service;
    private volatile boolean scpMode;   // route transfers over SCP instead of SFTP (listing stays SFTP)

    SftpFileSystem(SftpService service) { this.service = service; }

    /** Switches file transfers between SFTP (false) and SCP (true) over the same SSH session. */
    void setScpMode(boolean scp) { this.scpMode = scp; }

    @Override public String name() { return "SFTP"; }

    @Override public String home() throws Exception { return service.home(); }

    @Override public String parent(String path) {
        if (path == null || path.equals("/") || path.isBlank()) return "/";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash);
    }

    @Override public String join(String dir, String name) {
        if (dir == null || dir.isBlank()) return "/" + name;
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    @Override public List<FileItem> list(String path) throws Exception {
        return service.list(path).stream()
                .map(e -> FileItem.of(e.name(), e.path(), e.directory(), e.size(), e.modified(), e.permissions()))
                .toList();
    }

    @Override public void mkdir(String path) throws Exception { service.mkdir(path); }

    @Override public void rename(String from, String to) throws Exception { service.rename(from, to); }

    @Override public void delete(FileItem item) throws Exception { service.deleteRecursive(item.path()); }

    @Override public boolean supportsContentAccess() { return true; }

    @Override public byte[] readFile(FileItem item, long maxBytes) throws Exception {
        return service.readBytes(item.path(), (int) Math.min(maxBytes, Integer.MAX_VALUE));
    }

    @Override public void writeFile(String dir, String name, byte[] data) throws Exception {
        service.writeBytes(join(dir, name), data);
    }

    @Override public boolean supportsChmod() { return true; }

    @Override public void chmod(FileItem item, int octalPermissions) throws Exception {
        service.chmod(item.path(), octalPermissions);
    }

    // ---- FileTransfer ----

    @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
        upload(localFile, remoteDir, localFile.getFileName().toString(), progress);
    }

    @Override public void upload(Path localFile, String remoteDir, String destName, LongConsumer progress) throws Exception {
        String remotePath = join(remoteDir, destName);
        if (scpMode) service.uploadScp(localFile, remotePath, progress);
        else service.upload(localFile, remotePath, progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
        download(remoteFile, localDir, remoteFile.name(), progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, String destName, LongConsumer progress) throws Exception {
        Path target = localDir.resolve(destName);
        if (scpMode) service.downloadScp(remoteFile.path(), target, progress);
        else service.download(remoteFile.path(), target, progress);
    }
}
