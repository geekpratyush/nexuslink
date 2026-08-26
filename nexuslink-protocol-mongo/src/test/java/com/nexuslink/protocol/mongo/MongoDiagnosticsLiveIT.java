package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pipeline preview, explain and server diagnostics against a real server. */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoDiagnosticsLiveIT {

    private MongoService service;

    @BeforeEach
    void setUp() {
        service = new MongoService();
        service.connect("mongodb://localhost:27017");
        service.useDatabase("nexuslink_diag_it");
        service.runShell("db.orders.drop()");
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            docs.add(new Document("n", i).append("status", i % 4 == 0 ? "open" : "closed")
                    .append("tags", List.of("a", "b")));
        }
        service.importDocuments("orders", docs, 100, null);
    }

    @AfterEach
    void tearDown() {
        service.runShell("db.orders.drop()");
        service.close();
    }

    @Test
    void thePreviewShowsTheCountAfterEachStage() {
        List<StagePreview> stages = service.previewPipeline("orders",
                "[{\"$match\":{\"status\":\"open\"}},{\"$unwind\":\"$tags\"},{\"$limit\":10}]", 5);
        assertEquals(3, stages.size());
        assertEquals(50, stages.get(0).count(), "a quarter of 200 are open");
        assertEquals(100, stages.get(1).count(), "$unwind doubles them");
        assertEquals(100 - 50, stages.get(1).delta());
        assertEquals(10, stages.get(2).count());
        assertFalse(stages.get(0).sample().isEmpty());
        assertTrue(stages.get(0).sample().size() <= 5, "the sample honours its limit");
    }

    @Test
    void theStageThatEmptiesThePipelineIsIdentified() {
        List<StagePreview> stages = service.previewPipeline("orders",
                "[{\"$match\":{\"status\":\"open\"}},{\"$match\":{\"status\":\"nope\"}}]", 5);
        assertEquals(50, stages.get(0).count());
        assertEquals(0, stages.get(1).count());
        assertTrue(stages.get(1).emptiedThePipeline());
    }

    @Test
    void aBrokenStageStopsTheWalkAndReportsTheServerError() {
        List<StagePreview> stages = service.previewPipeline("orders",
                "[{\"$match\":{}},{\"$group\":{\"_id\":null,\"x\":{\"$notAnAccumulator\":1}}},{\"$limit\":1}]", 5);
        assertEquals(2, stages.size(), "the walk stops at the failure");
        assertTrue(stages.get(1).isFailed());
        assertNotNull(stages.get(1).error());
    }

    @Test
    void explainNamesTheCollectionScanAndThenTheIndex() {
        ExplainSummary scan = service.explainSummary("orders", "{\"status\":\"open\"}");
        assertTrue(scan.isCollectionScan(), scan.stage());
        assertTrue(scan.verdict().contains("Collection scan"));
        assertEquals(200, scan.documentsExamined());

        service.runShell("db.orders.createIndex({\"status\":1})");
        ExplainSummary indexed = service.explainSummary("orders", "{\"status\":\"open\"}");
        assertTrue(indexed.indexUsed(), indexed.stage());
        assertEquals("status_1", indexed.indexName());
        assertEquals(50, indexed.documentsReturned());
    }

    @Test
    void aggregateExplainReturnsAPlan() {
        String plan = service.explainAggregate("orders", "[{\"$match\":{\"status\":\"open\"}}]");
        assertFalse(plan.isBlank());
        assertTrue(plan.contains("stage") || plan.contains("queryPlanner"), plan.substring(0, 120));
    }

    @Test
    void aStandaloneReportsItselfRatherThanShowingAnEmptyTopology() {
        List<String> status = service.topologyStatus();
        assertEquals(1, status.size());
        assertTrue(status.get(0).contains("Standalone"), status.get(0));
    }

    @Test
    void currentOpAndTheProfilerAnswer() {
        assertFalse(service.currentOperations(0).isEmpty());
        service.setProfilingLevel(1, 0);
        assertTrue(service.profilingStatus().startsWith("level 1"), service.profilingStatus());
        service.runShell("db.orders.find({\"status\":\"open\"})");
        assertFalse(service.slowOperations(10).isEmpty());
        service.setProfilingLevel(0, 100);
    }
}
