package com.nexuslink.protocol.ftp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live FTP round-trip against the local {@code test-env} vsftpd server
 * (user nexus / nexus123, passive mode, host port 21).
 * <pre>docker compose -f test-env/docker-compose.yml up -d ftp</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class FtpLiveIT {

    @Test
    void uploadListReadAndDelete() throws Exception {
        Path local = Files.createTempFile("nexus-ftp", ".txt");
        Files.writeString(local, "hello-ftp");
        String remote = "nexus-it.txt";
        try (FtpService svc = new FtpService()) {
            svc.connect("localhost", 21, "nexus", "nexus123", true, false);
            assertTrue(svc.isConnected());

            svc.upload(local, remote, bytes -> {});

            List<FtpService.FtpEntry> entries = svc.list(".");
            assertTrue(entries.stream().anyMatch(e -> e.name().equals("nexus-it.txt")));

            assertEquals("hello-ftp", svc.readText(remote, 4096));

            svc.deleteFile(remote);
            assertTrue(svc.list(".").stream().noneMatch(e -> e.name().equals("nexus-it.txt")));
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
