package com.nexuslink.protocol.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
