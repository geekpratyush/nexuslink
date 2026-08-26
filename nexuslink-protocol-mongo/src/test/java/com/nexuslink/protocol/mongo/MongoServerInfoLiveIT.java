package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server-capability layer against a real MongoDB (the {@code test-env} container): proves the
 * deployment is identified from its own replies and that collection statistics come back through
 * whichever route that version supports.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoServerInfoLiveIT {

    private MongoService service;

    @BeforeEach
    void setUp() {
        service = new MongoService();
        service.connect("mongodb://localhost:27017");
        service.useDatabase("nexuslink_it");
        service.insertOne("stats_probe", "{\"n\": 1}");
    }

    @AfterEach
    void tearDown() {
        try { service.deleteMany("stats_probe", "{}"); } catch (RuntimeException ignored) { }
        service.close();
    }

    @Test
    void theServerIdentifiesItself() {
        MongoServerInfo info = service.serverInfo();
        assertEquals("MongoDB", info.product());
        assertFalse(info.version().isBlank(), "a live server reports its version");
        assertTrue(info.major() >= 4, "unexpectedly old test server: " + info.version());
        assertFalse(info.label().isBlank());
    }

    @Test
    void collectionStatsComeBackWhicheverRouteTheVersionNeeds() {
        Map<String, String> stats = service.collectionStats("stats_probe");
        assertEquals("1", stats.get("Documents"));
        assertNotNull(stats.get("Storage size"));
        assertNotNull(stats.get("Indexes"));
    }

    @Test
    void serverInfoIsCachedForTheConnection() {
        assertSame(service.serverInfo(), service.serverInfo());
    }
}
