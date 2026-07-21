package com.nexuslink.protocol.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * FTP / FTPS client over Apache Commons Net. Connect with host/port + username/password (anonymous
 * works with empty/"anonymous" credentials); passive mode and FTPS (explicit TLS) are supported.
 */
public final class FtpService implements AutoCloseable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** One directory entry. */
    public record FtpEntry(String name, String path, boolean directory, long size, String modified) {}

    private FTPClient ftp;

    public void connect(String host, int port, String username, String password, boolean passive, boolean tls) throws Exception {
        close();
        FTPClient client = tls ? new FTPSClient(false) : new FTPClient();
        client.connect(host, port);
        int reply = client.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            client.disconnect();
            throw new IllegalStateException("FTP server refused connection: " + reply);
        }
        String user = username == null || username.isBlank() ? "anonymous" : username;
        if (!client.login(user, password == null ? "" : password)) {
            String msg = client.getReplyString();
            client.disconnect();
            throw new IllegalStateException("Login failed: " + msg.trim());
        }
        if (passive) client.enterLocalPassiveMode();
        client.setFileType(FTP.BINARY_FILE_TYPE);
        this.ftp = client;
    }

    public boolean isConnected() { return ftp != null && ftp.isConnected(); }

    /** Lists a directory (excluding "." and ".."). */
    public List<FtpEntry> list(String path) throws Exception {
        String dir = path == null || path.isBlank() ? "/" : path;
        List<FtpEntry> entries = new ArrayList<>();
        for (FTPFile f : ftp.listFiles(dir)) {
            if (f == null) continue;
            String name = f.getName();
            if (name.equals(".") || name.equals("..")) continue;
            String full = dir.endsWith("/") ? dir + name : dir + "/" + name;
            String modified = f.getTimestampInstant() == null ? ""
                    : FMT.format(f.getTimestampInstant().atZone(java.time.ZoneId.systemDefault()));
            entries.add(new FtpEntry(name, full, f.isDirectory(), f.getSize(), modified));
        }
        entries.sort((x, y) -> x.directory() == y.directory()
                ? x.name().compareToIgnoreCase(y.name()) : (x.directory() ? -1 : 1));
        return entries;
    }

    /** Downloads a file's first {@code maxBytes} as UTF-8 text (for previews). */
    public String readText(String path, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ftp.retrieveFile(path, out);
        byte[] bytes = out.toByteArray();
        int len = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Downloads a file's first {@code maxBytes} raw bytes (for the quick-view / edit-in-place dialog). */
    public byte[] readBytes(String path, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ftp.retrieveFile(path, out);
        byte[] bytes = out.toByteArray();
        int len = Math.min(bytes.length, maxBytes);
        if (len == bytes.length) return bytes;
        byte[] trimmed = new byte[len];
        System.arraycopy(bytes, 0, trimmed, 0, len);
        return trimmed;
    }

    /** Stores {@code data} to {@code path}, creating or overwriting it (edit-in-place save). */
    public void writeBytes(String path, byte[] data) throws Exception {
        try (InputStream in = new java.io.ByteArrayInputStream(data)) {
            if (!ftp.storeFile(path, in)) {
                throw new IllegalStateException("Server refused upload: " + ftp.getReplyString().trim());
            }
        }
    }

    /** The current remote working directory (used as the browser's start path). */
    public String pwd() throws Exception {
        String wd = ftp.printWorkingDirectory();
        return wd == null || wd.isBlank() ? "/" : wd;
    }

    /** Downloads a remote file to a local path, reporting bytes transferred to {@code progress}. */
    public void download(String remotePath, Path localTarget, LongConsumer progress) throws Exception {
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        try (InputStream in = ftp.retrieveFileStream(remotePath);
             OutputStream out = Files.newOutputStream(localTarget)) {
            if (in == null) throw new IllegalStateException("Server refused download: " + ftp.getReplyString().trim());
            copy(in, out, progress);
        }
        if (!ftp.completePendingCommand()) throw new IllegalStateException("Download did not complete: " + ftp.getReplyString().trim());
    }

    /** Uploads a local file to a remote path, reporting bytes transferred to {@code progress}. */
    public void upload(Path localSource, String remotePath, LongConsumer progress) throws Exception {
        try (InputStream in = Files.newInputStream(localSource);
             OutputStream out = ftp.storeFileStream(remotePath)) {
            if (out == null) throw new IllegalStateException("Server refused upload: " + ftp.getReplyString().trim());
            copy(in, out, progress);
        }
        if (!ftp.completePendingCommand()) throw new IllegalStateException("Upload did not complete: " + ftp.getReplyString().trim());
    }

    /**
     * Uploads {@code localSource} from byte {@code offset} onward using FTP {@code APPE}, adding to the
     * partial remote file. {@code progress} reports the remote file's total size (offset + bytes sent
     * here). An {@code offset} of 0 delegates to the ordinary truncating {@link #upload}.
     */
    public void uploadFrom(Path localSource, String remotePath, long offset, LongConsumer progress) throws Exception {
        if (offset <= 0) { upload(localSource, remotePath, progress); return; }
        try (InputStream in = Files.newInputStream(localSource);
             OutputStream out = ftp.appendFileStream(remotePath)) {
            if (out == null) throw new IllegalStateException("Server refused append: " + ftp.getReplyString().trim());
            in.skipNBytes(offset);                       // resend nothing already on the server
            copy(in, out, shifted(progress, offset));
        }
        if (!ftp.completePendingCommand()) throw new IllegalStateException("Resumed upload did not complete: " + ftp.getReplyString().trim());
    }

    /**
     * Downloads {@code remotePath} from byte {@code offset} onward using FTP {@code REST}, appending to
     * the partial local file. Not every server honours REST — the offset is cleared afterwards either
     * way so a later whole-file transfer is unaffected.
     */
    public void downloadFrom(String remotePath, Path localTarget, long offset, LongConsumer progress) throws Exception {
        if (offset <= 0) { download(remotePath, localTarget, progress); return; }
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        ftp.setRestartOffset(offset);
        try (InputStream in = ftp.retrieveFileStream(remotePath);
             OutputStream out = Files.newOutputStream(localTarget,
                     java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND)) {
            if (in == null) throw new IllegalStateException("Server refused resumed download: " + ftp.getReplyString().trim());
            copy(in, out, shifted(progress, offset));
        } finally {
            ftp.setRestartOffset(0);                     // sticky on the client — clear it for the next transfer
        }
        if (!ftp.completePendingCommand()) throw new IllegalStateException("Resumed download did not complete: " + ftp.getReplyString().trim());
    }

    /** True when the server advertises the {@code REST} command needed to resume a download. */
    public boolean supportsRestart() throws Exception {
        return ftp.hasFeature("REST");
    }

    /** Rebases a progress callback so it reports total file bytes rather than this call's own count. */
    private static LongConsumer shifted(LongConsumer progress, long offset) {
        return progress == null ? null : n -> progress.accept(offset + n);
    }

    private static void copy(InputStream in, OutputStream out, LongConsumer progress) throws Exception {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            if (progress != null) progress.accept(total);
        }
    }

    /** Creates a remote directory. */
    public void mkdir(String path) throws Exception {
        if (!ftp.makeDirectory(path)) throw new IllegalStateException("mkdir failed: " + ftp.getReplyString().trim());
    }

    /** Renames (or moves) a remote file or directory. */
    public void rename(String from, String to) throws Exception {
        if (!ftp.rename(from, to)) throw new IllegalStateException("rename failed: " + ftp.getReplyString().trim());
    }

    /** Removes a remote file. */
    public void deleteFile(String path) throws Exception {
        if (!ftp.deleteFile(path)) throw new IllegalStateException("delete failed: " + ftp.getReplyString().trim());
    }

    /** Removes a remote directory (must be empty). */
    public void deleteDir(String path) throws Exception {
        if (!ftp.removeDirectory(path)) throw new IllegalStateException("rmdir failed: " + ftp.getReplyString().trim());
    }

    /** Recursively removes a remote file or directory tree. */
    public void deleteRecursive(FtpEntry entry) throws Exception {
        if (entry.directory()) {
            for (FtpEntry child : list(entry.path())) deleteRecursive(child);
            deleteDir(entry.path());
        } else {
            deleteFile(entry.path());
        }
    }

    @Override
    public void close() {
        if (ftp != null) {
            try { if (ftp.isConnected()) { ftp.logout(); ftp.disconnect(); } } catch (Exception ignored) { }
            ftp = null;
        }
    }
}
