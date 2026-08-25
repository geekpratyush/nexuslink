package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestCollectionStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsATreeThroughDisk() {
        Path file = dir.resolve("nested/rest-collections.json");
        RestCollectionStore store = new RestCollectionStore(file);
        CollectionNode api = CollectionNode.folder("API");
        api.children.add(CollectionNode.request("Health",
                new ObjectMapper().createObjectNode().put("url", "https://api/health")));
        store.collections().add(api);
        store.save();
        assertNull(store.lastError());

        RestCollectionStore reopened = new RestCollectionStore(file);
        assertEquals(1, reopened.collections().size());
        CollectionNode read = reopened.collections().get(0);
        assertTrue(read.folder);
        assertEquals("API", read.name);
        assertEquals("https://api/health", read.children.get(0).request.path("url").asText());
        assertEquals(api.children.get(0).id, read.children.get(0).id);
    }

    @Test
    void missingFileStartsEmptyWithoutError() {
        RestCollectionStore store = new RestCollectionStore(dir.resolve("absent.json"));
        assertTrue(store.collections().isEmpty());
        assertNull(store.lastError());
    }

    @Test
    void corruptFileReportsRatherThanThrows() throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{not json");
        RestCollectionStore store = new RestCollectionStore(file);
        assertTrue(store.collections().isEmpty());
        assertNotNull(store.lastError());
    }

    @Test
    void unknownFieldsSurviveAnOlderReader() throws IOException {
        Path file = dir.resolve("future.json");
        Files.writeString(file, """
                {"collections":[{"id":"a","name":"API","folder":true,"children":[],"tags":["x"]}],
                 "schemaVersion":9}
                """);
        RestCollectionStore store = new RestCollectionStore(file);
        assertEquals("API", store.collections().get(0).name);
    }
}
