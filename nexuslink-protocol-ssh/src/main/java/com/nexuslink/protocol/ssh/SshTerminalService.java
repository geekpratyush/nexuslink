package com.nexuslink.protocol.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * An interactive SSH shell session over Apache MINA SSHD — the data plane behind the terminal view.
 * Connect with host/port + password (or an OpenSSH private key), which opens a PTY-backed shell
 * channel; keystrokes are {@link #write(String) written} to the remote and a background reader pumps
 * remote output to a registered byte consumer (the view feeds it into a {@link VtScreen}).
 *
 * <p>One instance drives one shell session; call {@link #close()} to tear it down. Resizing the view
 * forwards a window-change ({@link #resize}) so the remote {@code $LINES/$COLUMNS} and curses apps
 * (vim, top, less) reflow correctly.
 */
public final class SshTerminalService implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private SshClient client;
    private ClientSession session;
    private ChannelShell channel;
    private OutputStream remoteIn;      // write here → remote stdin
    private Thread reader;
    private volatile boolean running;

    private Consumer<byte[]> onOutput = b -> {};
    private Runnable onClosed = () -> {};

    /** Registers the sink for raw remote output bytes. Set before connecting. */
    public void setOnOutput(Consumer<byte[]> onOutput) {
        this.onOutput = onOutput == null ? b -> {} : onOutput;
    }

    /** Registers a callback fired once when the remote shell/reader ends (EOF or error). */
    public void setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed == null ? () -> {} : onClosed;
    }

    /** Connects and opens a PTY shell authenticated with a password. */
    public void connect(String host, int port, String username, String password, int cols, int rows) throws Exception {
        openSession(host, port, username);
        if (password != null) session.addPasswordIdentity(password);
        session.auth().verify(TIMEOUT);
        openShell(cols, rows);
    }

    /** Connects and opens a PTY shell authenticated with an OpenSSH private-key file. */
    public void connectWithKey(String host, int port, String username, Path privateKey,
                               int cols, int rows) throws Exception {
        openSession(host, port, username);
        org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser.INSTANCE
                .loadKeyPairs(session, privateKey, null)
                .forEach(session::addPublicKeyIdentity);
        session.auth().verify(TIMEOUT);
        openShell(cols, rows);
    }

    private void openSession(String host, int port, String username) throws Exception {
        close();
        client = SshClient.setUpDefaultClient();
        client.start();
        session = client.connect(username, host, port).verify(TIMEOUT).getSession();
    }

    private void openShell(int cols, int rows) throws Exception {
        channel = session.createShellChannel();
        channel.setUsePty(true);
        channel.setPtyType("xterm-256color");
        channel.setPtyColumns(Math.max(1, cols));
        channel.setPtyLines(Math.max(1, rows));
        channel.open().verify(TIMEOUT);
        remoteIn = channel.getInvertedIn();
        running = true;
        reader = new Thread(() -> pump(channel.getInvertedOut()), "ssh-terminal-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void pump(InputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while (running && (n = out.read(buf)) != -1) {
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                onOutput.accept(chunk);
            }
        } catch (IOException ignored) {
            // channel closed / connection dropped
        } finally {
            running = false;
            onClosed.run();
        }
    }

    public boolean isConnected() {
        return running && session != null && session.isOpen();
    }

    /** Sends text (typed keys, pasted content, control sequences) to the remote shell. */
    public void write(String data) throws IOException {
        if (remoteIn == null) throw new IOException("not connected");
        remoteIn.write(data.getBytes(StandardCharsets.UTF_8));
        remoteIn.flush();
    }

    /** Sends raw bytes (e.g. an encoded key) to the remote shell. */
    public void write(byte[] data) throws IOException {
        if (remoteIn == null) throw new IOException("not connected");
        remoteIn.write(data);
        remoteIn.flush();
    }

    /**
     * Opens a local port forward: connections to {@code localhost:localPort} are tunnelled over the SSH
     * session to {@code remoteHost:remotePort}. Returns the actually-bound local address.
     */
    public String startLocalForward(int localPort, String remoteHost, int remotePort) throws IOException {
        if (session == null) throw new IOException("not connected");
        var bound = session.startLocalPortForwarding(
                new org.apache.sshd.common.util.net.SshdSocketAddress("localhost", localPort),
                new org.apache.sshd.common.util.net.SshdSocketAddress(remoteHost, remotePort));
        return bound.toString();
    }

    /** Forwards a terminal resize to the remote PTY so curses apps reflow. */
    public void resize(int cols, int rows) {
        try {
            if (channel != null) channel.sendWindowChange(Math.max(1, cols), Math.max(1, rows));
        } catch (IOException ignored) {
            // best-effort; a dropped channel is handled by the reader
        }
    }

    @Override
    public void close() {
        running = false;
        try { if (channel != null) channel.close(false); } catch (Exception ignored) { }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (client != null) client.stop(); } catch (Exception ignored) { }
        channel = null; session = null; client = null; remoteIn = null;
    }
}
