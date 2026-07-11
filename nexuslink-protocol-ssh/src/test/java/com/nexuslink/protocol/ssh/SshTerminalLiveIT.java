package com.nexuslink.protocol.ssh;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end SSH terminal test driving {@link SshTerminalService} against an <em>embedded</em> Apache
 * MINA SSHD server in the same JVM — no Docker or external host required. The server runs a real
 * {@code /bin/sh} as the shell channel, so this exercises the full client stack: connect → password
 * auth → open shell channel → write commands → read remote output.
 *
 * <p>Gated on {@code -Dnexuslink.it=true} and POSIX (needs {@code /bin/sh}). Run:
 * <pre>mvn -pl nexuslink-protocol-ssh test -Dnexuslink.it=true -Dtest=SshTerminalLiveIT</pre>
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@EnabledOnOs({OS.LINUX, OS.MAC})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SshTerminalLiveIT {

    private static SshServer server;
    private static int port;

    @BeforeAll
    static void startServer(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0); // ephemeral
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((PasswordAuthenticator)
                (username, password, session) -> "tester".equals(username) && "secret".equals(password));
        server.setShellFactory(new ProcessShellFactory("/bin/sh", List.of("/bin/sh")));
        server.start();
        port = server.getPort();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) server.stop(true);
    }

    @Test
    void connectRunCommandAndReadOutput() throws Exception {
        StringBuilder out = new StringBuilder();
        SshTerminalService svc = new SshTerminalService();
        svc.setOnOutput(bytes -> {
            synchronized (out) { out.append(new String(bytes, StandardCharsets.UTF_8)); }
        });

        svc.connect("127.0.0.1", port, "tester", "secret", 80, 24);
        assertTrue(svc.isConnected());

        try {
            // /bin/sh reads commands from stdin; echo a unique marker and quit.
            String marker = "NEXUS_" + System.nanoTime();
            svc.write("echo " + marker + "\n");
            svc.write("exit\n");

            boolean seen = false;
            for (int i = 0; i < 50 && !seen; i++) {
                synchronized (out) { seen = out.indexOf(marker) >= 0; }
                if (!seen) Thread.sleep(100);
            }
            synchronized (out) {
                assertTrue(out.indexOf(marker) >= 0, "remote echo output should reach the client: <" + out + ">");
            }

            // Feed the captured output through the screen model — the marker should render on the grid.
            VtScreen screen = new VtScreen(24, 80);
            synchronized (out) { screen.feed(out.toString()); }
            assertTrue(screen.text().contains(marker), "screen model should render the echoed marker");
        } finally {
            svc.close();
        }
    }
}
