package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** GridFS upload/download/rename/delete against a real server. */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoGridFsLiveIT {

    @TempDir Path tempDir;

    private MongoService service;

    @BeforeEach
    void setUp() {
        service = new MongoService();
        service.connect("mongodb://localhost:27017");
        service.useDatabase("nexuslink_gridfs_it");
        service.gridFsDropBucket("fs");
        service.gridFsDropBucket("assets");
    }

    @AfterEach
    void tearDown() {
        service.gridFsDropBucket("fs");
        service.gridFsDropBucket("assets");
        service.close();
    }

    @Test
    void uploadThenDownloadRoundTripsTheBytes() throws Exception {
        Path source = tempDir.resolve("report.txt");
        String content = "hello gridfs\n".repeat(5000);   // ~65 KB, more than one chunk
        Files.writeString(source, content);

        AtomicLong sent = new AtomicLong();
        service.gridFsUpload("assets", "report.txt", source, sent::set);
        assertEquals(content.length(), sent.get());

        Path target = tempDir.resolve("out.txt");
        AtomicLong received = new AtomicLong();
        service.gridFsDownload("assets", "report.txt", target, received::set);
        assertEquals(content, Files.readString(target));
        assertEquals(content.length(), received.get());
    }

    @Test
    void bucketsAndFilesAreListed() {
        service.gridFsWrite("assets", "a.txt", "a".getBytes(StandardCharsets.UTF_8));
        service.gridFsWrite("fs", "b.txt", "bb".getBytes(StandardCharsets.UTF_8));

        List<String> buckets = service.gridFsBuckets();
        assertTrue(buckets.contains("assets"), buckets.toString());
        assertTrue(buckets.contains("fs"), buckets.toString());

        List<MongoService.GridFsEntry> files = service.gridFsList("assets");
        assertEquals(1, files.size());
        assertEquals("a.txt", files.get(0).filename());
        assertEquals(1, files.get(0).length());
        assertNotNull(files.get(0).uploadDate());
    }

    @Test
    void readingHonoursItsByteLimit() throws Exception {
        service.gridFsWrite("assets", "big.txt", "0123456789".getBytes(StandardCharsets.UTF_8));
        assertEquals("01234", new String(service.gridFsRead("assets", "big.txt", 5), StandardCharsets.UTF_8));
        assertEquals(10, service.gridFsRead("assets", "big.txt", 1000).length);
    }

    @Test
    void renameAndDeleteAffectEveryRevision() {
        service.gridFsWrite("assets", "dup.txt", "one".getBytes(StandardCharsets.UTF_8));
        service.gridFsWrite("assets", "dup.txt", "two".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, service.gridFsList("assets").size(), "GridFS keeps revisions");

        service.gridFsRename("assets", "dup.txt", "renamed.txt");
        assertTrue(service.gridFsList("assets").stream().allMatch(f -> f.filename().equals("renamed.txt")));

        service.gridFsDelete("assets", "renamed.txt");
        assertTrue(service.gridFsList("assets").isEmpty());
    }

    @Test
    void droppingABucketRemovesItFromTheListing() {
        service.gridFsWrite("assets", "x", new byte[]{1});
        assertTrue(service.gridFsBuckets().contains("assets"));
        service.gridFsDropBucket("assets");
        assertFalse(service.gridFsBuckets().contains("assets"));
    }
}
