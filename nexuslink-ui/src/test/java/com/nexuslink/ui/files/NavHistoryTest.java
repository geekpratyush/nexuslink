package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavHistoryTest {

    @Test
    void emptyHistoryGoesNowhere() {
        NavHistory h = new NavHistory();
        assertNull(h.current());
        assertFalse(h.canGoBack());
        assertFalse(h.canGoForward());
        assertNull(h.back());
        assertNull(h.forward());
    }

    @Test
    void visitAdvancesAndBackForwardWalk() {
        NavHistory h = new NavHistory();
        h.visit("/a");
        h.visit("/a/b");
        h.visit("/a/b/c");
        assertEquals("/a/b/c", h.current());
        assertTrue(h.canGoBack());
        assertFalse(h.canGoForward());

        assertEquals("/a/b", h.back());
        assertEquals("/a", h.back());
        assertNull(h.back(), "at oldest entry");
        assertEquals("/a", h.current());
        assertTrue(h.canGoForward());

        assertEquals("/a/b", h.forward());
        assertEquals("/a/b/c", h.forward());
        assertNull(h.forward(), "at newest entry");
    }

    @Test
    void revisitingCurrentIsNoOp() {
        NavHistory h = new NavHistory();
        h.visit("/a");
        h.visit("/a");   // e.g. a refresh() of the same directory
        assertEquals(List.of("/a"), h.snapshot());
        assertFalse(h.canGoBack());
    }

    @Test
    void visitingNewPathTruncatesForwardBranch() {
        NavHistory h = new NavHistory();
        h.visit("/a");
        h.visit("/b");
        h.visit("/c");
        h.back();               // now at /b, forward branch = [/c]
        h.visit("/d");          // abandons /c
        assertEquals(List.of("/a", "/b", "/d"), h.snapshot());
        assertEquals("/d", h.current());
        assertFalse(h.canGoForward());
        assertEquals("/b", h.back());
    }

    @Test
    void nullAndBlankPathsIgnored() {
        NavHistory h = new NavHistory();
        h.visit(null);
        h.visit("   ");
        assertNull(h.current());
        assertTrue(h.snapshot().isEmpty());
    }
}
