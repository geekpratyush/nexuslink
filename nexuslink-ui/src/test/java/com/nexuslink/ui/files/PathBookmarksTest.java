package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathBookmarksTest {

    @Test
    void addsAndListsInOrder() {
        PathBookmarks b = new PathBookmarks().add("Home", "/home/u").add("Logs", "/var/log");
        assertEquals(List.of("/home/u", "/var/log"), b.list().stream().map(PathBookmarks.Bookmark::path).toList());
        assertEquals("Home", b.list().get(0).label());
    }

    @Test
    void blankLabelDefaultsToLastSegment() {
        PathBookmarks b = new PathBookmarks().add("", "/var/log/nginx");
        assertEquals("nginx", b.list().get(0).label());
        b.add(null, "/opt/app/");
        assertEquals("app", b.list().get(1).label(), "trailing slash trimmed");
    }

    @Test
    void pathsAreUniqueAndReAddUpdatesLabel() {
        PathBookmarks b = new PathBookmarks().add("First", "/data").add("Renamed", "/data");
        assertEquals(1, b.size());
        assertEquals("Renamed", b.list().get(0).label());
    }

    @Test
    void removeAndContains() {
        PathBookmarks b = new PathBookmarks().add("x", "/a").add("y", "/b");
        assertTrue(b.contains("/a"));
        assertTrue(b.remove("/a"));
        assertFalse(b.contains("/a"));
        assertFalse(b.remove("/a"));
        assertEquals(1, b.size());
    }

    @Test
    void blankPathIsIgnored() {
        PathBookmarks b = new PathBookmarks().add("x", "  ").add("y", null);
        assertEquals(0, b.size());
    }

    @Test
    void serializeParseRoundTrip() {
        PathBookmarks b = new PathBookmarks().add("Home", "/home/u").add("Logs", "/var/log");
        PathBookmarks back = PathBookmarks.parse(b.serialize());
        assertEquals(b.list(), back.list());
    }

    @Test
    void parseSkipsBlankAndMalformedLines() {
        PathBookmarks b = PathBookmarks.parse("Home\t/home/u\n\nno-tab-here\nLogs\t/var/log\n");
        assertEquals(2, b.size());
        assertEquals(List.of("/home/u", "/var/log"), b.list().stream().map(PathBookmarks.Bookmark::path).toList());
    }

    @Test
    void tabsAndNewlinesInFieldsAreSanitised() {
        String s = new PathBookmarks().add("a\tb\nc", "/p").serialize();
        assertEquals(1, s.chars().filter(ch -> ch == '\t').count(), "only the field separator tab remains");
        assertEquals("a b c", PathBookmarks.parse(s).list().get(0).label());
    }

    @Test
    void loadMissingFileGivesEmpty(@TempDir Path dir) {
        assertEquals(0, PathBookmarks.load(dir.resolve("nope.txt")).size());
    }

    @Test
    void saveThenLoadRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/bookmarks.txt");
        new PathBookmarks().add("Home", "/home/u").add("Tmp", "/tmp").save(file);
        PathBookmarks loaded = PathBookmarks.load(file);
        assertEquals(2, loaded.size());
        assertEquals("/home/u", loaded.list().get(0).path());
    }
}
