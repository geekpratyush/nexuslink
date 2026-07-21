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

    /**
     * Proves offset-based resume against a real SFTP server rather than a stubbed transport: half a file
     * is placed remotely to stand in for an interrupted upload, then {@code uploadFrom} appends the rest
     * and the result must equal the original byte-for-byte. This is the part the queue's unit tests
     * cannot cover — that {@code OpenMode.Append} really appends and the skip really skips.
     */
    @Test
    void resumedUploadAppendsExactlyTheMissingTail() throws Exception {
        String payload = "0123456789abcdefghijklmnopqrstuvwxyz";
        Path local = localDir.resolve("resume-src.txt");
        Files.writeString(local, payload);
        int landed = 10;

        try (SftpService svc = new SftpService()) {
            svc.connect("127.0.0.1", port, "u", "pw");
            svc.writeBytes("/partial.txt", payload.substring(0, landed).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            java.util.List<Long> reported = new java.util.ArrayList<>();
            svc.uploadFrom(local, "/partial.txt", landed, reported::add);

            assertEquals(payload, svc.readText("/partial.txt", 4096), "the resumed file must match the source");
            assertEquals(payload.length(), reported.get(reported.size() - 1),
                    "progress reports total bytes present, not just this call's contribution");
            assertTrue(reported.get(0) > landed, "and it starts above the offset rather than back at zero");
        }
    }

    /** The mirror case: a partial local file is completed by downloading only the missing tail. */
    @Test
    void resumedDownloadAppendsExactlyTheMissingTail() throws Exception {
        String payload = "the quick brown fox jumps over the lazy dog";
        int landed = 16;

        try (SftpService svc = new SftpService()) {
            svc.connect("127.0.0.1", port, "u", "pw");
            svc.writeBytes("/whole.txt", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Path partial = localDir.resolve("resume-dst.txt");
            Files.writeString(partial, payload.substring(0, landed));

            svc.downloadFrom("/whole.txt", partial, landed, bytes -> {});

            assertEquals(payload, Files.readString(partial));
        }
    }

    /** An offset of zero is an ordinary whole-file transfer, so callers need no special-casing. */
    @Test
    void zeroOffsetFallsBackToAWholeFileTransfer() throws Exception {
        Path local = localDir.resolve("zero-offset.txt");
        Files.writeString(local, "written from scratch");

        try (SftpService svc = new SftpService()) {
            svc.connect("127.0.0.1", port, "u", "pw");
            svc.uploadFrom(local, "/from-zero.txt", 0, bytes -> {});
            assertEquals("written from scratch", svc.readText("/from-zero.txt", 4096));
        }
    }
}
