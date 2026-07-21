package com.nexuslink.ui.ftp;

import com.nexuslink.protocol.ftp.FtpService;
import com.nexuslink.ui.files.FileItem;
import com.nexuslink.ui.files.FileSystem;
import com.nexuslink.ui.files.FileTransfer;

import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Adapts {@link FtpService} to the generic {@link FileSystem}/{@link FileTransfer} contracts so an
 * FTP/FTPS server can drive one side of the {@link com.nexuslink.ui.files.DualPaneBrowser}.
 */
final class FtpFileSystem implements FileSystem, FileTransfer {

    private final FtpService service;

    FtpFileSystem(FtpService service) { this.service = service; }

    @Override public String name() { return "FTP"; }

    @Override public String home() throws Exception { return service.pwd(); }

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
                .map(e -> FileItem.of(e.name(), e.path(), e.directory(), e.size(), e.modified(), ""))
                .toList();
    }

    @Override public void mkdir(String path) throws Exception { service.mkdir(path); }

    @Override public void rename(String from, String to) throws Exception { service.rename(from, to); }

    @Override public void delete(FileItem item) throws Exception {
        service.deleteRecursive(new FtpService.FtpEntry(item.name(), item.path(), item.directory(), item.size(), item.modified()));
    }

    @Override public boolean supportsContentAccess() { return true; }

    @Override public byte[] readFile(FileItem item, long maxBytes) throws Exception {
        return service.readBytes(item.path(), (int) Math.min(maxBytes, Integer.MAX_VALUE));
    }

    @Override public void writeFile(String dir, String name, byte[] data) throws Exception {
        service.writeBytes(join(dir, name), data);
    }

    // ---- FileTransfer ----

    @Override public void upload(Path localFile, String remoteDir, LongConsumer progress) throws Exception {
        upload(localFile, remoteDir, localFile.getFileName().toString(), progress);
    }

    @Override public void upload(Path localFile, String remoteDir, String destName, LongConsumer progress) throws Exception {
        service.upload(localFile, join(remoteDir, destName), progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, LongConsumer progress) throws Exception {
        download(remoteFile, localDir, remoteFile.name(), progress);
    }

    @Override public void download(FileItem remoteFile, Path localDir, String destName, LongConsumer progress) throws Exception {
        service.download(remoteFile.path(), localDir.resolve(destName), progress);
    }

    /**
     * Uploads resume via {@code APPE}, which every FTP server supports; downloads need {@code REST},
     * which not all do. A server that hides REST simply transfers whole files.
     */
    @Override public boolean supportsResume() {
        try { return service.supportsRestart(); }
        catch (Exception e) { return false; }
    }

    @Override public void uploadFrom(Path localFile, String remoteDir, String destName, long offset, LongConsumer progress) throws Exception {
        service.uploadFrom(localFile, join(remoteDir, destName), offset, progress);
    }

    @Override public void downloadFrom(FileItem remoteFile, Path localDir, String destName, long offset, LongConsumer progress) throws Exception {
        service.downloadFrom(remoteFile.path(), localDir.resolve(destName), offset, progress);
    }
}
