package com.nexuslink.protocol.sftp;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Self-contained end-to-end test of SFTP + <b>SCP</b> transfer mode against an <em>embedded</em> Apache
 * MINA SSHD server in the same JVM (SFTP subsystem for listing + an SCP command factory over a virtual
 * file system rooted at a temp dir). No Docker or external host — this always runs in the normal build
 * and proves {@link SftpService#uploadScp}/{@link SftpService#downloadScp} really move bytes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SftpScpEmbeddedTest {

    private SshServer server;
    private int port;
    private Path serverRoot;   // created manually: @TempDir instance fields aren't injected before a PER_CLASS @BeforeAll
    @TempDir Path localDir;

    @BeforeAll
    void startServer() throws Exception {
        serverRoot = Files.createTempDirectory("nexus-scp-root");
        Path hostKey = Files.createTempFile("nexus-scp-hostkey", ".ser");
        Files.deleteIfExists(hostKey); // MINA generates + writes the key at this path
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((u, p, s) -> "u".equals(u) && "pw".equals(p));
        server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setCommandFactory(new ScpCommandFactory());
        server.start();
        port = server.getPort();
    }

    @AfterAll
    void stopServer() throws Exception {
        if (server != null) server.stop(true);
    }

    @Test
    void scpUploadListDownloadRoundTrip() throws Exception {
        Path local = localDir.resolve("scp-src.txt");
        Files.writeString(local, "hello-over-scp");

        try (SftpService svc = new SftpService()) {
            svc.connect("127.0.0.1", port, "u", "pw");
            assertTrue(svc.isConnected());

            // Upload over SCP, then confirm the file is visible via the SFTP listing.
            svc.uploadScp(local, "/uploaded.txt", bytes -> {});
            assertTrue(svc.list("/").stream().anyMatch(e -> e.name().equals("uploaded.txt")),
                    "SCP-uploaded file should be listed over SFTP");

            // Read it back over SFTP...
            assertEquals("hello-over-scp", svc.readText("/uploaded.txt", 4096));

            // ...and download it over SCP to a fresh local path.
            Path downloaded = localDir.resolve("scp-dst.txt");
            svc.downloadScp("/uploaded.txt", downloaded, bytes -> {});
            assertEquals("hello-over-scp", Files.readString(downloaded));

            svc.deleteFile("/uploaded.txt");
            assertTrue(svc.list("/").stream().noneMatch(e -> e.name().equals("uploaded.txt")));
        }
    }
}
