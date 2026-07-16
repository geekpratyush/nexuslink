package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiskSpaceTest {

    @Test
    void summaryShowsFreeTotalAndPercent() {
        // 15 GiB free of 100 GiB → 15%.
        DiskSpace s = new DiskSpace(15L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024);
        assertEquals("15.0 GB free of 100.0 GB (15%)", s.summary());
    }

    @Test
    void freeFractionIsClampedAndComputed() {
        assertEquals(0.25, new DiskSpace(25, 100).freeFraction(), 1e-9);
        assertEquals(0.0, new DiskSpace(10, 0).freeFraction(), 1e-9);
        assertEquals(1.0, new DiskSpace(200, 100).freeFraction(), 1e-9);
    }

    @Test
    void summaryOmitsPercentWhenTotalUnknown() {
        assertEquals(FileItem.humanSize(500) + " free", new DiskSpace(500, 0).summary());
    }

    @Test
    void percentRoundsToNearestWhole() {
        // 1 free of 3 → 33.33% → 33%.
        assertTrue(new DiskSpace(1, 3).summary().endsWith("(33%)"));
    }
}
