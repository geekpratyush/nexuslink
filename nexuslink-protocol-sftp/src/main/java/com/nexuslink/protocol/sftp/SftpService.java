package com.nexuslink.protocol.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * SFTP client over Apache MINA SSHD. Connect with host/port + password (or an SSH private key),
 * then list directories and read files. Holds one SSH session + SFTP channel for the browse session.
 */
public final class SftpService implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** One directory entry. */
    public record SftpEntry(String name, String path, boolean directory, long size, String modified, String permissions) {}

    private SshClient client;
    private ClientSession session;
    private SftpClient sftp;

    public void connect(String host, int port, String username, String password) throws Exception {
        close();
        client = SshClient.setUpDefaultClient();
        client.start();
        session = client.connect(username, host, port).verify(TIMEOUT).getSession();
        if (password != null) session.addPasswordIdentity(password);
        session.auth().verify(TIMEOUT);
        sftp = SftpClientFactory.instance().createSftpClient(session);
    }

    /** Authenticates with an SSH private-key file instead of a password. */
    public void connectWithKey(String host, int port, String username, Path privateKey) throws Exception {
        close();
        client = SshClient.setUpDefaultClient();
        client.start();
        session = client.connect(username, host, port).verify(TIMEOUT).getSession();
        org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser.INSTANCE
                .loadKeyPairs(session, privateKey, null)
                .forEach(session::addPublicKeyIdentity);
        session.auth().verify(TIMEOUT);
        sftp = SftpClientFactory.instance().createSftpClient(session);
    }

    public boolean isConnected() { return sftp != null && session != null && session.isOpen(); }

    /** Lists a directory (excluding "." and ".."). */
    public List<SftpEntry> list(String path) throws Exception {
        List<SftpEntry> entries = new ArrayList<>();
        String dir = path == null || path.isBlank() ? "/" : path;
        for (SftpClient.DirEntry e : sftp.readDir(dir)) {
            String name = e.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            SftpClient.Attributes a = e.getAttributes();
            String full = dir.endsWith("/") ? dir + name : dir + "/" + name;
            entries.add(new SftpEntry(name, full, a.isDirectory(),
                    a.getSize(),
                    a.getModifyTime() == null ? "" : a.getModifyTime().toString(),
                    permString(a.getPermissions())));
        }
        entries.sort((x, y) -> x.directory() == y.directory()
                ? x.name().compareToIgnoreCase(y.name()) : (x.directory() ? -1 : 1));
        return entries;
    }

    /** Reads up to {@code maxBytes} of a file as UTF-8 text (for previews). */
    public String readText(String path, int maxBytes) throws Exception {
        try (InputStream in = sftp.read(path)) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Reads up to {@code maxBytes} of a file's raw bytes (for the quick-view / edit-in-place dialog). */
    public byte[] readBytes(String path, int maxBytes) throws Exception {
        try (InputStream in = sftp.read(path)) {
            return in.readNBytes(maxBytes);
        }
    }

    /** Writes {@code data} to {@code path}, creating or truncating it (edit-in-place save). */
    public void writeBytes(String path, byte[] data) throws Exception {
        try (OutputStream out = sftp.write(path)) {
            out.write(data);
        }
    }

    /** Downloads a remote file to a local path, reporting bytes transferred to {@code progress}. */
    public void download(String remotePath, Path localTarget, LongConsumer progress) throws Exception {
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        try (InputStream in = sftp.read(remotePath);
             OutputStream out = Files.newOutputStream(localTarget)) {
            copy(in, out, progress);
        }
    }

    /** Uploads a local file to a remote path, reporting bytes transferred to {@code progress}. */
    public void upload(Path localSource, String remotePath, LongConsumer progress) throws Exception {
        try (InputStream in = Files.newInputStream(localSource);
             OutputStream out = sftp.write(remotePath)) {
            copy(in, out, progress);
        }
    }

    // ---- SCP transfer mode (same SSH session, different file-copy protocol than SFTP) ----

    /**
     * Uploads {@code localSource} to {@code remotePath} over SCP rather than SFTP, reusing the open SSH
     * session. SCP has no incremental progress callback, so {@code progress} is invoked once with the
     * final byte count on success.
     */
    public void uploadScp(Path localSource, String remotePath, LongConsumer progress) throws Exception {
        scpClient().upload(localSource, remotePath,
                java.util.Collections.<org.apache.sshd.scp.client.ScpClient.Option>emptySet());
        if (progress != null) progress.accept(Files.size(localSource));
    }

    /**
     * Downloads {@code remotePath} to {@code localTarget} over SCP rather than SFTP. Like
     * {@link #uploadScp}, {@code progress} is reported once at completion.
     */
    public void downloadScp(String remotePath, Path localTarget, LongConsumer progress) throws Exception {
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        scpClient().download(remotePath, localTarget,
                java.util.Collections.<org.apache.sshd.scp.client.ScpClient.Option>emptySet());
        if (progress != null && Files.exists(localTarget)) progress.accept(Files.size(localTarget));
    }

    private org.apache.sshd.scp.client.ScpClient scpClient() {
        if (session == null || !session.isOpen()) throw new IllegalStateException("not connected");
        return org.apache.sshd.scp.client.ScpClientCreator.instance().createScpClient(session);
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
    public void mkdir(String path) throws Exception { sftp.mkdir(path); }

    /** Renames (or moves) a remote file or directory. */
    public void rename(String from, String to) throws Exception { sftp.rename(from, to); }

    /** Removes a remote file. */
    public void deleteFile(String path) throws Exception { sftp.remove(path); }

    /** Removes a remote directory (must be empty). */
    public void deleteDir(String path) throws Exception { sftp.rmdir(path); }

    /** Recursively removes a remote file or directory tree. */
    public void deleteRecursive(String path) throws Exception {
        SftpClient.Attributes a = sftp.stat(path);
        if (a.isDirectory()) {
            for (SftpEntry child : list(path)) deleteRecursive(child.path());
            sftp.rmdir(path);
        } else {
            sftp.remove(path);
        }
    }

    /** Changes the POSIX permission bits (e.g. {@code 0644}) of a remote file. */
    public void chmod(String path, int octalPermissions) throws Exception {
        SftpClient.Attributes attrs = new SftpClient.Attributes();
        attrs.setPermissions(octalPermissions);
        sftp.setStat(path, attrs);
    }

    /** Resolves the remote working directory (the user's home), falling back to {@code /}. */
    public String home() throws Exception {
        try { return sftp.canonicalPath("."); }
        catch (Exception e) { return "/"; }
    }

    /** Returns the absolute, canonical form of a remote path. */
    public String canonical(String path) throws Exception { return sftp.canonicalPath(path); }

    /** Parses an octal-permission string like {@code "0644"} or {@code "644"} into bits. */
    public static int parseOctal(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? 0 : Integer.parseInt(t, 8);
    }

    /** Renders the low 9 permission bits as an {@code rwxr-xr-x}-style string. */
    public static String rwxString(int perms) { return permString(perms).substring(1); }

    private static String permString(int perms) {
        char[] s = "----------".toCharArray();
        s[0] = (perms & 0x4000) != 0 ? 'd' : '-';
        int[] bits = {0400, 0200, 0100, 040, 020, 010, 04, 02, 01};
        char[] rwx = {'r', 'w', 'x'};
        for (int i = 0; i < 9; i++) if ((perms & bits[i]) != 0) s[i + 1] = rwx[i % 3];
        return new String(s);
    }

    @Override
    public void close() {
        try { if (sftp != null) sftp.close(); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (client != null) client.stop(); } catch (Exception ignored) { }
        sftp = null; session = null; client = null;
    }
}
