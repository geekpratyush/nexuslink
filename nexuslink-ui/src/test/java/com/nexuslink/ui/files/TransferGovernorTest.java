package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransferGovernorTest {

    @Test
    void throttleSleepsToHoldTheConfiguredRate() {
        long[] clock = {0};
        List<Long> slept = new ArrayList<>();
        TransferGovernor g = new TransferGovernor(() -> clock[0], slept::add);
        g.setMaxBytesPerSecond(1000);      // 1000 B/s
        g.startTransfer();

        g.tick(0);                          // first chunk establishes the baseline — no sleep
        assertTrue(slept.isEmpty());

        // 2000 bytes should take 2s at 1000 B/s; with zero elapsed time it must sleep ~2000 ms.
        g.tick(2000);
        assertEquals(List.of(2000L), slept);
    }

    @Test
    void throttleDoesNotSleepWhenAlreadySlowEnough() {
        long[] clock = {0};
        List<Long> slept = new ArrayList<>();
        TransferGovernor g = new TransferGovernor(() -> clock[0], slept::add);
        g.setMaxBytesPerSecond(1000);
        g.startTransfer();
        g.tick(0);
        clock[0] = 5_000_000_000L;          // 5s elapsed, only 1000 bytes → well under the cap
        g.tick(1000);
        assertTrue(slept.isEmpty());
    }

    @Test
    void unlimitedRateNeverSleeps() {
        List<Long> slept = new ArrayList<>();
        TransferGovernor g = new TransferGovernor(() -> 0L, slept::add);
        g.setMaxBytesPerSecond(0);          // unlimited
        g.startTransfer();
        g.tick(0);
        g.tick(10_000_000);
        assertTrue(slept.isEmpty());
    }

    @Test
    void pauseBlocksTickUntilResumed() throws Exception {
        TransferGovernor g = new TransferGovernor();
        g.pause();
        assertTrue(g.isPaused());

        AtomicBoolean returned = new AtomicBoolean(false);
        Thread t = new Thread(() -> { g.tick(100); returned.set(true); });
        t.start();

        Thread.sleep(150);
        assertFalse(returned.get(), "tick must stay blocked while paused");

        g.resume();
        t.join(1000);
        assertFalse(g.isPaused());
        assertTrue(returned.get(), "tick must return once resumed");
    }
}
