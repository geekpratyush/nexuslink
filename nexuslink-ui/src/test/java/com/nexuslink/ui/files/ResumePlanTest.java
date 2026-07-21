package com.nexuslink.ui.files;

import com.nexuslink.ui.files.ResumePlan.Action;
import com.nexuslink.ui.files.ResumePlan.Plan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResumePlanTest {

    @Test
    void resumesFromThePartialLength() {
        Plan p = ResumePlan.of(1000, 400, true);
        assertEquals(Action.RESUME_FROM, p.action());
        assertEquals(400, p.offset());
        assertEquals(600, p.remaining(1000));
    }

    @Test
    void sameSizeMeansNothingLeftToSend() {
        Plan p = ResumePlan.of(1000, 1000, true);
        assertEquals(Action.ALREADY_COMPLETE, p.action());
        assertEquals(0, p.remaining(1000));
    }

    @Test
    void missingOrEmptyDestinationSendsTheWholeFile() {
        for (long destSize : new long[]{0, -1}) {
            Plan p = ResumePlan.of(1000, destSize, true);
            assertEquals(Action.TRANSFER_WHOLE, p.action(), "destSize=" + destSize);
            assertEquals(0, p.offset());
            assertEquals(1000, p.remaining(1000));
        }
    }

    @Test
    void aLongerDestinationCannotBeAPrefixSoTheWholeFileIsResent() {
        // Appending here would splice a foreign tail onto our file, so restart instead.
        Plan p = ResumePlan.of(1000, 1500, true);
        assertEquals(Action.TRANSFER_WHOLE, p.action());
        assertEquals(0, p.offset());
    }

    @Test
    void unknownSourceSizeSendsTheWholeFile() {
        assertEquals(Action.TRANSFER_WHOLE, ResumePlan.of(0, 400, true).action());
        assertEquals(Action.TRANSFER_WHOLE, ResumePlan.of(-1, 400, true).action());
    }

    @Test
    void disabledAlwaysSendsTheWholeFile() {
        assertEquals(Action.TRANSFER_WHOLE, ResumePlan.of(1000, 400, false).action());
        assertEquals(Action.TRANSFER_WHOLE, ResumePlan.of(1000, 1000, false).action(),
                "a complete destination is still re-sent when resume is off — that is the overwrite path's call");
    }

    @Test
    void oneByteShortStillResumes() {
        Plan p = ResumePlan.of(1000, 999, true);
        assertEquals(Action.RESUME_FROM, p.action());
        assertEquals(999, p.offset());
        assertEquals(1, p.remaining(1000));
    }

    @Test
    void summaryReadsAsANote() {
        assertEquals("resuming at 1.0 KB", ResumePlan.of(4096, 1024, true).summary());
        assertEquals("already complete", ResumePlan.of(100, 100, true).summary());
        assertEquals("sending whole file", ResumePlan.of(100, 0, true).summary());
    }
}
