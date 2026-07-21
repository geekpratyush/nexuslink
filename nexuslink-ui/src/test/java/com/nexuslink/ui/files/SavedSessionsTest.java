package com.nexuslink.ui.files;

import com.nexuslink.ui.files.SavedSessions.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SavedSessionsTest {

    private static Session prod() { return Session.of("prod", "example.com", 22, "deploy", ""); }
    private static Session staging() { return Session.of("staging", "stage.example.com", 2222, "dev", "/home/u/.ssh/id_ed25519"); }

    @Test
    void savesAndListsInOrder() {
        SavedSessions s = new SavedSessions().add(prod()).add(staging());
        assertEquals(List.of("prod", "staging"), s.list().stream().map(Session::name).toList());
    }

    @Test
    void namesAreUniqueAndResavingReplacesInPlace() {
        SavedSessions s = new SavedSessions().add(prod()).add(staging())
                .add(Session.of("PROD", "new-host.example.com", 22, "root", ""));
        assertEquals(2, s.size(), "case-insensitive name match replaces rather than duplicating");
        assertEquals("new-host.example.com", s.find("prod").orElseThrow().host());
        assertEquals(0, s.list().indexOf(s.find("prod").orElseThrow()), "replacement keeps its position");
    }

    @Test
    void blankNamedSessionIsIgnored() {
        SavedSessions s = new SavedSessions().add(Session.of("  ", "h", 22, "u", "")).add(null);
        assertEquals(0, s.size());
    }

    @Test
    void findAndRemoveAreCaseInsensitive() {
        SavedSessions s = new SavedSessions().add(prod());
        assertTrue(s.find("PrOd").isPresent());
        assertTrue(s.find("missing").isEmpty());
        assertTrue(s.remove("PROD"));
        assertFalse(s.remove("prod"));
        assertEquals(0, s.size());
    }

    @Test
    void rememberDirsUpdatesOnlyTheDirectories() {
        SavedSessions s = new SavedSessions().add(staging());
        assertTrue(s.rememberDirs("staging", "/home/u/work", "/srv/app"));
        Session got = s.find("staging").orElseThrow();
        assertEquals("/home/u/work", got.lastLocalDir());
        assertEquals("/srv/app", got.lastRemoteDir());
        assertEquals("/home/u/.ssh/id_ed25519", got.keyPath(), "rest of the profile survives");
        assertEquals(2222, got.port());
        assertEquals(1, s.size());
    }

    @Test
    void rememberDirsForUnknownSessionIsANoOp() {
        SavedSessions s = new SavedSessions().add(prod());
        assertFalse(s.rememberDirs("never-saved", "/a", "/b"), "callers may record unconditionally");
        assertEquals(1, s.size());
    }

    @Test
    void optionsCarryProtocolFlags() {
        Session ftp = Session.of("ftp", "h", 21, "anonymous", "")
                .withOptions(Map.of("passive", "true", "tls", "false"));
        assertTrue(ftp.flag("passive", false));
        assertFalse(ftp.flag("tls", true));
        assertTrue(ftp.flag("absent", true), "missing flag falls back");
        assertFalse(ftp.flag("absent", false));
    }

    @Test
    void targetLabelsUserHostPort() {
        assertEquals("deploy@example.com:22", prod().target());
        assertEquals("example.com:22", Session.of("n", "example.com", 22, "", "").target());
    }

    @Test
    void serializeParseRoundTripIncludingDirsAndOptions() {
        SavedSessions s = new SavedSessions()
                .add(prod().withDirs("/home/u", "/var/www").withOptions(Map.of("scp", "true")))
                .add(staging());
        SavedSessions back = SavedSessions.parse(s.serialize());
        assertEquals(s.list(), back.list());
    }

    @Test
    void parseSkipsBlankAndNamelessLinesAndIgnoresUnknownKeys() {
        SavedSessions s = SavedSessions.parse(
                "name=a\thost=h1\tport=22\tuser=u\n"
                        + "\n"
                        + "host=orphan\tport=22\n"                  // no name → skipped
                        + "just some noise\n"                       // no key=value → no name → skipped
                        + "name=b\thost=h2\tport=21\tuser=u\tfuture=whatever\n");
        assertEquals(List.of("a", "b"), s.list().stream().map(Session::name).toList());
        assertEquals("h2", s.find("b").orElseThrow().host());
    }

    @Test
    void parseToleratesMissingAndUnparseablePort() {
        SavedSessions s = SavedSessions.parse("name=a\thost=h\n" + "name=b\thost=h\tport=nonsense\n");
        assertEquals(0, s.find("a").orElseThrow().port());
        assertEquals(0, s.find("b").orElseThrow().port());
    }

    @Test
    void valuesMayContainEqualsSigns() {
        Session s = SavedSessions.parse("name=a\thost=h\tport=22\tuser=u\tremote=/srv/a=b\n").find("a").orElseThrow();
        assertEquals("/srv/a=b", s.lastRemoteDir(), "only the first = splits a field");
    }

    @Test
    void tabsAndNewlinesInFieldsAreSanitised() {
        String text = new SavedSessions().add(Session.of("a\tb\nc", "h", 22, "u", "")).serialize();
        assertEquals("a b c", SavedSessions.parse(text).list().get(0).name());
        assertEquals(1, text.lines().count(), "an embedded newline cannot split the record");
    }

    @Test
    void noPasswordFieldIsEverWritten() {
        String text = new SavedSessions().add(prod().withDirs("/home/u", "/srv")).serialize();
        assertFalse(text.toLowerCase().contains("pass"), "sessions must never persist secrets");
    }

    @Test
    void loadMissingFileGivesEmpty(@TempDir Path dir) {
        assertEquals(0, SavedSessions.load(dir.resolve("nope.txt")).size());
    }

    @Test
    void saveThenLoadRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/sessions.txt");
        new SavedSessions().add(prod()).add(staging().withDirs("/home/u", "/srv")).save(file);
        SavedSessions loaded = SavedSessions.load(file);
        assertEquals(2, loaded.size());
        assertEquals("/srv", loaded.find("staging").orElseThrow().lastRemoteDir());
    }

    @Test
    void fileForSanitisesTheProtocolName() {
        assertTrue(SavedSessions.fileFor("sftp").endsWith("sessions-sftp.txt"));
        assertTrue(SavedSessions.fileFor("../evil").endsWith("sessions-___evil.txt"), "no path traversal");
    }
}
