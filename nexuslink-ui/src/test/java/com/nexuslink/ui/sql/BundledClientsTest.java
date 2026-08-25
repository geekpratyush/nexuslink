package com.nexuslink.ui.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BundledClientsTest {

    @Test
    void redisAndMongoAreReportedAsBundled() {
        var names = BundledClients.all().stream().map(BundledClients.Client::name).toList();
        assertEquals(java.util.List.of("Redis", "MongoDB"), names);
    }

    @Test
    void eachClientNamesItsDriver() {
        var redis = BundledClients.all().get(0);
        assertEquals("Lettuce", redis.driver());
        assertTrue(redis.describe().startsWith("Redis — Lettuce"), redis.describe());
    }

    @Test
    void aMissingManifestVersionNeverRendersAsNull() {
        // Dev builds load classes from directories with no Implementation-Version.
        for (var client : BundledClients.all()) {
            assertNotNull(client.version());
            assertFalse(client.describe().contains("null"), client.describe());
        }
    }

    @Test
    void summaryListsEveryClient() {
        String summary = BundledClients.summary();
        assertTrue(summary.contains("Redis"), summary);
        assertTrue(summary.contains("MongoDB"), summary);
    }
}
