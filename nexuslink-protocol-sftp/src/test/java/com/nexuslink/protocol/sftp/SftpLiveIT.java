package com.nexuslink.protocol.sftp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live SFTP round-trip against the local {@code test-env} atmoz/sftp server
 * (user nexus / nexus, writable /upload, host port 2222).
 * <pre>docker compose -f test-env/docker-compose.yml up -d sftp</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class SftpLiveIT {

    @Test
    void uploadListReadAndDelete() throws Exception {
        Path local = Files.createTempFile("nexus-sftp", ".txt");
        Files.writeString(local, "hello-sftp");
        String remote = "/upload/nexus-it.txt";
        try (SftpService svc = new SftpService()) {
            svc.connect("localhost", 2222, "nexus", "nexus");
            assertTrue(svc.isConnected());

            svc.upload(local, remote, bytes -> {});

            List<SftpService.SftpEntry> entries = svc.list("/upload");
            assertTrue(entries.stream().anyMatch(e -> e.name().equals("nexus-it.txt")));

            assertEquals("hello-sftp", svc.readText(remote, 4096));

            svc.deleteFile(remote);
            assertTrue(svc.list("/upload").stream().noneMatch(e -> e.name().equals("nexus-it.txt")));
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
