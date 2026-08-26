package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StagePreviewTest {

    @Test
    void theFirstStageHasNoDeltaBecauseThereIsNothingBeforeIt() {
        StagePreview first = new StagePreview(0, "$match", 240, -1, List.of(), 3, null);
        assertEquals(0, first.delta());
        assertEquals("$match — 240 docs", first.summary());
    }

    @Test
    void aDropInCountIsShownAsANegativeDelta() {
        StagePreview stage = new StagePreview(1, "$match", 240, 12431, List.of(), 3, null);
        assertEquals(-12191, stage.delta());
        assertTrue(stage.summary().contains("−12191"), stage.summary());
    }

    @Test
    void anIncreaseIsShownAsAPositiveDelta() {
        StagePreview unwound = new StagePreview(1, "$unwind", 900, 300, List.of(), 3, null);
        assertEquals(600, unwound.delta());
        assertTrue(unwound.summary().contains("(+600)"), unwound.summary());
    }

    @Test
    void theStageThatEmptiesThePipelineIsCalledOut() {
        StagePreview empty = new StagePreview(3, "$match", 0, 240, List.of(), 2, null);
        assertTrue(empty.emptiedThePipeline());
        assertTrue(empty.summary().contains("nothing survives this stage"), empty.summary());
    }

    @Test
    void anAlreadyEmptyPipelineDoesNotBlameTheNextStage() {
        StagePreview stage = new StagePreview(4, "$sort", 0, 0, List.of(), 1, null);
        assertFalse(stage.emptiedThePipeline());
    }

    @Test
    void aFailedStageCarriesItsError() {
        StagePreview failed = StagePreview.failed(2, "$group", "unknown accumulator", 5);
        assertTrue(failed.isFailed());
        assertEquals(-1, failed.count());
        assertTrue(failed.summary().contains("unknown accumulator"));
    }

    @Test
    void aSingleDocumentReadsAsSingular() {
        assertTrue(new StagePreview(0, "$count", 1, -1, List.of(), 1, null).summary().contains("1 doc"));
    }
}
