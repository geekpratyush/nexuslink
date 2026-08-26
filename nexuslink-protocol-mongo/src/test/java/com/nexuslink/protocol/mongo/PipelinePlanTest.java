package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelinePlanTest {

    private static final String PIPELINE = """
            [ {"$match": {"role": "admin"}},
              {"$sort": {"name": 1}},
              {"$limit": 10} ]""";

    @Test
    void parsesEachStageOfTheArray() {
        List<Document> stages = PipelinePlan.parse(PIPELINE);
        assertEquals(3, stages.size());
        assertEquals("$match", PipelinePlan.stageName(stages.get(0)));
        assertEquals("$limit", PipelinePlan.stageName(stages.get(2)));
    }

    @Test
    void anEmptyPipelineParsesToNothing() {
        assertTrue(PipelinePlan.parse("").isEmpty());
        assertTrue(PipelinePlan.parse(null).isEmpty());
        assertTrue(PipelinePlan.parse("[]").isEmpty());
    }

    @Test
    void aStageWithTwoOperatorsIsRejectedByPosition() {
        PipelinePlan.PipelineException e = assertThrows(PipelinePlan.PipelineException.class,
                () -> PipelinePlan.parse("[{\"$match\":{},\"$sort\":{}}]"));
        assertEquals(0, e.stageIndex());
        assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
    }

    @Test
    void aStageWithoutADollarOperatorIsRejected() {
        PipelinePlan.PipelineException e = assertThrows(PipelinePlan.PipelineException.class,
                () -> PipelinePlan.parse("[{\"$match\":{}},{\"match\":{}}]"));
        assertEquals(1, e.stageIndex(), "the second stage is the broken one");
    }

    @Test
    void malformedJsonIsReportedWithoutAStageNumber() {
        PipelinePlan.PipelineException e = assertThrows(PipelinePlan.PipelineException.class,
                () -> PipelinePlan.parse("not an array"));
        assertEquals(-1, e.stageIndex());
    }

    @Test
    void aPrefixIsEveryStageUpToAndIncludingTheIndex() {
        List<Document> stages = PipelinePlan.parse(PIPELINE);
        assertEquals(1, PipelinePlan.prefix(stages, 0).size());
        assertEquals(3, PipelinePlan.prefix(stages, 2).size());
        assertTrue(PipelinePlan.prefix(stages, 9).isEmpty());
        assertTrue(PipelinePlan.prefix(stages, -1).isEmpty());
    }

    @Test
    void theSampleQueryAppendsALimit() {
        List<Document> sample = PipelinePlan.sampleAt(PipelinePlan.parse(PIPELINE), 1, 5);
        assertEquals(3, sample.size(), "two stages plus the sample limit");
        assertEquals("$limit", PipelinePlan.stageName(sample.get(2)));
        assertEquals(5, sample.get(2).get("$limit"));
    }

    @Test
    void aPipelineEndingInCountNeedsNoSampleLimitOrCountStage() {
        List<Document> stages = PipelinePlan.parse("[{\"$match\":{}},{\"$count\":\"n\"}]");
        assertEquals(2, PipelinePlan.sampleAt(stages, 1, 5).size());
        assertEquals(2, PipelinePlan.countAt(stages, 1).size());
    }

    @Test
    void theCountQueryAppendsACountStage() {
        List<Document> counted = PipelinePlan.countAt(PipelinePlan.parse(PIPELINE), 0);
        assertEquals(2, counted.size());
        assertEquals("$count", PipelinePlan.stageName(counted.get(1)));
    }

    @Test
    void theStagesThatChangeTheDocumentCountAreKnown() {
        assertTrue(PipelinePlan.changesCount(new Document("$match", new Document())));
        assertTrue(PipelinePlan.changesCount(new Document("$group", new Document())));
        assertFalse(PipelinePlan.changesCount(new Document("$addFields", new Document())));
    }

    @Test
    void renderingRoundTripsBackToAnArray() {
        List<Document> stages = PipelinePlan.parse(PIPELINE);
        String rendered = PipelinePlan.render(stages);
        assertTrue(rendered.startsWith("[\n"));
        assertEquals(3, PipelinePlan.parse(rendered).size());
        assertEquals("[]", PipelinePlan.render(List.of()));
    }
}
