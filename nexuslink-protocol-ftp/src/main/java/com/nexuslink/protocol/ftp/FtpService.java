package com.nexuslink.protocol.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    @Override
    public void close() {
        if (ftp != null) {
            try { if (ftp.isConnected()) { ftp.logout(); ftp.disconnect(); } } catch (Exception ignored) { }
            ftp = null;
        }
    }
}
