package com.nexuslink.core.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreTest {

    @Test
    void addAndQueryRecent(@TempDir Path dir) {
        try (HistoryStore store = new HistoryStore(dir.resolve("h.db").toString())) {
            store.add(HistoryEntry.newRest("GET /users → 200", 200, 120, "{\"url\":\"/users\"}"));
            store.add(HistoryEntry.newRest("POST /orders → 201", 201, 240, "{\"url\":\"/orders\"}"));

            assertEquals(2, store.count());
            List<HistoryEntry> recent = store.recent(10);
            assertEquals(2, recent.size());
            // newest first
            assertTrue(recent.get(0).summary().contains("/orders"));
        }
    }

    @Test
    void generatedIdAndReplayDetailPersist(@TempDir Path dir) {
        try (HistoryStore store = new HistoryStore(dir.resolve("h.db").toString())) {
            HistoryEntry e = store.add(HistoryEntry.newRest("GET /x → 200", 200, 10, "{\"replay\":true}"));
            assertTrue(e.id() > 0);
            assertEquals("{\"replay\":true}", store.byId(e.id()).orElseThrow().detail());
        }
    }

    @Test
    void searchFindsByKeyword(@TempDir Path dir) {
        try (HistoryStore store = new HistoryStore(dir.resolve("h.db").toString())) {
            store.add(HistoryEntry.newRest("GET https://api.kafka.io/topics → 200", 200, 50, "{}"));
            store.add(HistoryEntry.newRest("GET https://api.payments.io/charges → 200", 200, 50, "{}"));

            List<HistoryEntry> hits = store.search("kafka", 10);
            assertEquals(1, hits.size());
            assertTrue(hits.get(0).summary().contains("kafka"));
        }
    }

    @Test
    void favoriteToggles(@TempDir Path dir) {
        try (HistoryStore store = new HistoryStore(dir.resolve("h.db").toString())) {
            HistoryEntry e = store.add(HistoryEntry.newRest("GET /fav → 200", 200, 10, "{}"));
            store.setFavorite(e.id(), true);
            assertTrue(store.byId(e.id()).orElseThrow().favorite());
        }
    }

    @Test
    void persistsAcrossReopen(@TempDir Path dir) {
        String db = dir.resolve("persist.db").toString();
        try (HistoryStore store = new HistoryStore(db)) {
            store.add(HistoryEntry.newRest("GET /persist → 200", 200, 10, "{}"));
        }
        try (HistoryStore reopened = new HistoryStore(db)) {
            assertEquals(1, reopened.count());
            assertTrue(reopened.recent(1).get(0).summary().contains("/persist"));
        }
    }
}
