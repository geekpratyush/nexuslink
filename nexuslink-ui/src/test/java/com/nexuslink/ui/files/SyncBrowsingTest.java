package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SyncBrowsingTest {

    @Test
    void descendMirrorsSameChildRelative() {
        // local /home/x -> /home/x/docs ; remote at /upload should go to /upload/docs
        assertEquals("/upload/docs", SyncBrowsing.mirror("/upload", "/home/x", "/home/x/docs"));
    }

    @Test
    void descendMultipleLevels() {
        assertEquals("/upload/a/b", SyncBrowsing.mirror("/upload", "/home/x", "/home/x/a/b"));
    }

    @Test
    void climbMirrorsSameNumberOfLevels() {
        // local /home/x/docs -> /home/x ; remote /upload/docs should go to /upload
        assertEquals("/upload", SyncBrowsing.mirror("/upload/docs", "/home/x/docs", "/home/x"));
    }

    @Test
    void siblingMoveClimbsThenDescends() {
        // /home/x/a -> /home/x/b ; remote /upload/a -> /upload/b
        assertEquals("/upload/b", SyncBrowsing.mirror("/upload/a", "/home/x/a", "/home/x/b"));
    }

    @Test
    void noCommonPrefixMirrorsAbsolutePath() {
        // Unrelated jump: the other pane ends up at the same absolute path.
        assertEquals("/a/b", SyncBrowsing.mirror("/p/q", "/home/x", "/a/b"));
    }

    @Test
    void unchangedSourceReturnsNull() {
        assertNull(SyncBrowsing.mirror("/upload", "/home/x", "/home/x"));
        assertNull(SyncBrowsing.mirror("/upload", "/home/x/", "/home/x"));  // trailing slash ignored
    }

    @Test
    void climbToRootDoesNotUnderflow() {
        assertEquals("/", SyncBrowsing.mirror("/upload", "/a/b", "/"));
    }

    @Test
    void segmentsIgnoreEmptyAndTrailing() {
        assertEquals(java.util.List.of("a", "b"), SyncBrowsing.segments("/a/b/"));
        assertTrue(SyncBrowsing.segments("/").isEmpty());
    }
}
