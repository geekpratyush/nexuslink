package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/** Schema profiling and index diagnostics against a real server. */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoAnalysisLiveIT {

    private MongoService service;

    @BeforeEach
    void setUp() {
        service = new MongoService();
        service.connect("mongodb://localhost:27017");
        service.useDatabase("nexuslink_analysis_it");
        service.runShell("db.mixed.drop()");
        service.runShell("db.mixed.insertMany(["
                + "{\"name\":\"ada\",\"age\":36,\"role\":\"admin\"},"
                + "{\"name\":\"bob\",\"age\":\"forty-one\"},"
                + "{\"name\":\"cleo\",\"age\":29,\"role\":\"user\",\"extra\":{\"team\":\"core\"}}]) ");
    }

    @AfterEach
    void tearDown() {
        service.runShell("db.mixed.drop()");
        service.close();
    }

    @Test
    void samplingFindsTheOptionalAndMixedTypeFields() {
        SchemaProfile profile = service.profileSchema("mixed", 100);
        assertEquals(3, profile.sampled());
        assertTrue(profile.polymorphicFields().stream().anyMatch(f -> f.path().equals("age")),
                "age is an int in two documents and a string in one");
        assertTrue(profile.optionalFields().stream().anyMatch(f -> f.path().equals("role")));
        assertTrue(profile.fields().stream().anyMatch(f -> f.path().equals("extra.team")),
                "nested fields are profiled by their dotted path");
    }

    @Test
    void indexStatsComeBackForARealCollection() {
        assertFalse(service.indexStats("mixed").isEmpty(), "at least the _id index reports usage");
        assertEquals(1, service.indexKeys("mixed").size());
    }

    @Test
    void anUnindexedQueryGetsARecommendationAndAnIndexedOneDoesNot() {
        String advice = service.indexRecommendation("mixed", "{\"role\":\"admin\"}", "");
        assertTrue(advice.startsWith("createIndex("), advice);

        service.runShell("db.mixed.createIndex({\"role\":1})");
        assertEquals("", service.indexRecommendation("mixed", "{\"role\":\"admin\"}", ""),
                "once the index exists there is nothing to suggest");
    }

    @Test
    void anIndexNothingHasUsedIsReportedAsADropCandidate() {
        service.runShell("db.mixed.createIndex({\"never_queried\":1})");
        var unused = IndexAdvice.unused(service.indexStats("mixed"));
        assertTrue(unused.stream().anyMatch(u -> u.name().equals("never_queried_1")), unused.toString());
        assertTrue(unused.stream().noneMatch(IndexAdvice.IndexUsage::isIdIndex));
    }
}
