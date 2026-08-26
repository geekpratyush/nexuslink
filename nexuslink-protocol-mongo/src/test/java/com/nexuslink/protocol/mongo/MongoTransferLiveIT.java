package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Collection import/export against a real server, including the round trip. */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoTransferLiveIT {

    @TempDir Path tempDir;

    private MongoService service;

    @BeforeEach
    void setUp() {
        service = new MongoService();
        service.connect("mongodb://localhost:27017");
        service.useDatabase("nexuslink_transfer_it");
        service.runShell("db.source.drop()");
        service.runShell("db.target.drop()");
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            docs.add(new Document("n", i).append("name", "user" + i)
                    .append("address", new Document("city", i % 2 == 0 ? "London" : "Delhi")));
        }
        service.importDocuments("source", docs, 100, null);
    }

    @AfterEach
    void tearDown() {
        service.runShell("db.source.drop()");
        service.runShell("db.target.drop()");
        service.close();
    }

    @Test
    void jsonLinesExportsEveryDocumentAndReportsProgress() throws Exception {
        Path file = tempDir.resolve("out.jsonl");
        AtomicLong lastProgress = new AtomicLong();
        long written = service.exportCollection("source", "", CollectionTransfer.Format.JSON_LINES,
                file, 200, lastProgress::set);
        assertEquals(250, written);
        assertEquals(250, Files.readAllLines(file).size());
        assertEquals(250, lastProgress.get());
    }

    @Test
    void aFilteredExportWritesOnlyTheMatchingDocuments() throws Exception {
        Path file = tempDir.resolve("filtered.jsonl");
        long written = service.exportCollection("source", "{\"address.city\":\"London\"}",
                CollectionTransfer.Format.JSON_LINES, file, 200, null);
        assertEquals(125, written);
    }

    @Test
    void jsonArrayExportReadsBackAsDocuments() throws Exception {
        Path file = tempDir.resolve("out.json");
        service.exportCollection("source", "", CollectionTransfer.Format.JSON_ARRAY, file, 200, null);
        List<Document> read = CollectionTransfer.fromJson(Files.readString(file));
        assertEquals(250, read.size());
    }

    @Test
    void csvExportWritesAHeaderAndDottedColumnsForNestedFields() throws Exception {
        Path file = tempDir.resolve("out.csv");
        service.exportCollection("source", "", CollectionTransfer.Format.CSV, file, 200, null);
        List<String> lines = Files.readAllLines(file);
        assertEquals(251, lines.size(), "a header plus every document");
        assertTrue(lines.get(0).contains("address.city"), lines.get(0));
    }

    @Test
    void exportThenImportRoundTripsIntoAnotherCollection() throws Exception {
        Path file = tempDir.resolve("round.jsonl");
        service.exportCollection("source", "", CollectionTransfer.Format.JSON_LINES, file, 200, null);
        List<Document> read = CollectionTransfer.fromJson(Files.readString(file));
        long inserted = service.importDocuments("target", read, 100, null);
        assertEquals(250, inserted);
        assertEquals(250, service.countDocuments("target", "{}"));
        assertEquals("London", ((Document) service.findDetailed("target",
                new MongoQuerySpec("{\"n\":0}", "", "", 0, 1)).documents().get(0).get("address")).get("city"));
    }
}
