package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OverwriteResolverTest {

    @Test
    void overwriteAndSkipAreNotSticky() {
        AtomicInteger prompts = new AtomicInteger();
        OverwriteResolver r = new OverwriteResolver(name -> {
            prompts.incrementAndGet();
            return OverwriteResolver.Choice.OVERWRITE;
        });

        assertEquals(OverwriteResolver.Action.OVERWRITE, r.resolve("a"));
        assertEquals(OverwriteResolver.Action.OVERWRITE, r.resolve("b"));
        assertFalse(r.isResolvedForAll());
        assertEquals(2, prompts.get(), "non-all answers must prompt every time");
    }

    @Test
    void overwriteAllStopsPromptingAndAlwaysOverwrites() {
        AtomicInteger prompts = new AtomicInteger();
        OverwriteResolver r = new OverwriteResolver(name -> {
            prompts.incrementAndGet();
            return OverwriteResolver.Choice.OVERWRITE_ALL;
        });

        assertEquals(OverwriteResolver.Action.OVERWRITE, r.resolve("a"));
        assertTrue(r.isResolvedForAll());
        assertEquals(OverwriteResolver.Action.OVERWRITE, r.resolve("b"));
        assertEquals(OverwriteResolver.Action.OVERWRITE, r.resolve("c"));
        assertEquals(1, prompts.get(), "an '…all' answer must prompt only once");
    }

    @Test
    void skipAllStopsPromptingAndAlwaysSkips() {
        AtomicInteger prompts = new AtomicInteger();
        OverwriteResolver r = new OverwriteResolver(name -> {
            prompts.incrementAndGet();
            return OverwriteResolver.Choice.SKIP_ALL;
        });

        assertEquals(OverwriteResolver.Action.SKIP, r.resolve("a"));
        assertEquals(OverwriteResolver.Action.SKIP, r.resolve("b"));
        assertEquals(1, prompts.get());
    }

    @Test
    void resetForgetsTheStickyDecision() {
        OverwriteResolver r = new OverwriteResolver(name -> OverwriteResolver.Choice.SKIP_ALL);
        r.resolve("a");
        assertTrue(r.isResolvedForAll());
        r.reset();
        assertFalse(r.isResolvedForAll());
    }

    @Test
    void alwaysOverwriteNeverPrompts() {
        OverwriteResolver r = OverwriteResolver.alwaysOverwrite();
        assertEquals(OverwriteResolver.Action.OVERWRITE, r.resolve("anything"));
    }
}
