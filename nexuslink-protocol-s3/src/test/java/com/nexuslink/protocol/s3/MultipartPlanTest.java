package com.nexuslink.protocol.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultipartPlanTest {

    private static final long MIB = 1024 * 1024;

    // ---- single-part (below threshold) --------------------------------------

    @Test
    void smallFileIsASinglePart() {
        MultipartPlan p = MultipartPlan.of(1234);
        assertFalse(p.multipart());
        assertEquals(1, p.partCount());
        assertEquals(1234, p.sizeOf(1));
        assertEquals(0, p.offsetOf(1));
    }

    @Test
    void emptyFileIsASinglePart() {
        MultipartPlan p = MultipartPlan.of(0);
        assertFalse(p.multipart());
        assertEquals(1, p.partCount());
        assertEquals(0, p.sizeOf(1));
    }

    @Test
    void justUnderTheThresholdStaysSinglePart() {
        MultipartPlan p = MultipartPlan.of(MultipartPlan.DEFAULT_THRESHOLD - 1);
        assertFalse(p.multipart());
    }

    @Test
    void atTheThresholdOneWholePartStaysASinglePut() {
        MultipartPlan p = MultipartPlan.of(MultipartPlan.DEFAULT_THRESHOLD);
        assertEquals(MultipartPlan.DEFAULT_PART_SIZE, p.partSize());
        assertEquals(1, p.partCount());
        assertFalse(p.multipart()); // one whole part — still cheaper as a plain PutObject
    }

    // ---- part splitting -----------------------------------------------------

    @Test
    void splitsIntoWholePartsPlusRemainder() {
        MultipartPlan p = MultipartPlan.of(20 * MIB, 8 * MIB, 8 * MIB);
        assertTrue(p.multipart());
        assertEquals(3, p.partCount());
        assertEquals(8 * MIB, p.sizeOf(1));
        assertEquals(8 * MIB, p.sizeOf(2));
        assertEquals(4 * MIB, p.sizeOf(3));
        assertEquals(0, p.offsetOf(1));
        assertEquals(8 * MIB, p.offsetOf(2));
        assertEquals(16 * MIB, p.offsetOf(3));
    }

    @Test
    void partSizesSumToTheTotal() {
        MultipartPlan p = MultipartPlan.of(37 * MIB + 17, 8 * MIB, 8 * MIB);
        long sum = 0;
        for (int i = 1; i <= p.partCount(); i++) sum += p.sizeOf(i);
        assertEquals(p.totalSize(), sum);
        assertEquals(p.totalSize(), p.offsetOf(p.partCount()) + p.sizeOf(p.partCount()));
    }

    @Test
    void exactMultipleHasNoShortLastPart() {
        MultipartPlan p = MultipartPlan.of(24 * MIB, 8 * MIB, 8 * MIB);
        assertEquals(3, p.partCount());
        assertEquals(8 * MIB, p.sizeOf(3));
    }

    // ---- clamping the preferred part size -----------------------------------

    @Test
    void tooSmallAPreferredPartSizeIsRaisedToTheS3Minimum() {
        MultipartPlan p = MultipartPlan.of(50 * MIB, MIB, 64 * 1024);
        assertEquals(MultipartPlan.MIN_PART_SIZE, p.partSize());
        assertEquals(10, p.partCount());
    }

    @Test
    void partSizeGrowsSoTheUploadStaysUnderTenThousandParts() {
        long total = 500L * 1024 * MIB;                       // 500 GiB
        MultipartPlan p = MultipartPlan.of(total, 8 * MIB, 8 * MIB);
        assertTrue(p.partCount() <= MultipartPlan.MAX_PARTS,
                "expected <= 10000 parts, got " + p.partCount());
        assertTrue(p.partSize() > 8 * MIB, "part size should have grown past the preferred 8 MiB");
        assertEquals(0, p.partSize() % MIB, "grown part size should land on a MiB boundary");
    }

    @Test
    void theLargestLegalObjectStillPlansWithinTheLimits() {
        MultipartPlan p = MultipartPlan.of(MultipartPlan.MAX_OBJECT_SIZE, 8 * MIB, 8 * MIB);
        assertTrue(p.partCount() <= MultipartPlan.MAX_PARTS);
        assertTrue(p.partSize() <= MultipartPlan.MAX_PART_SIZE);
        assertEquals(MultipartPlan.MAX_OBJECT_SIZE,
                p.offsetOf(p.partCount()) + p.sizeOf(p.partCount()));
    }

    // ---- rejections ---------------------------------------------------------

    @Test
    void negativeSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MultipartPlan.of(-1));
    }

    @Test
    void oversizedObjectIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MultipartPlan.of(MultipartPlan.MAX_OBJECT_SIZE + 1));
    }

    @Test
    void partNumberOutOfRangeIsRejected() {
        MultipartPlan p = MultipartPlan.of(20 * MIB, 8 * MIB, 8 * MIB);
        assertThrows(IllegalArgumentException.class, () -> p.sizeOf(0));
        assertThrows(IllegalArgumentException.class, () -> p.offsetOf(p.partCount() + 1));
    }
}
