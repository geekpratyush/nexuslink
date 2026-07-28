package com.nexuslink.protocol.mqtt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MqttHistoryStoreTest {

    @TempDir
    Path dir;

    private static MqttHistoryEntry msg(String payload) {
        return new MqttHistoryEntry(1, MqttHistoryEntry.Direction.RECEIVED, "a/b", 0, false, payload);
    }

    @Test
    @DisplayName("appended entries survive a reload, in order")
    void appendThenLoad() {
        MqttHistoryStore store = new MqttHistoryStore(dir.resolve("mqtt-history.log"));
        store.append(msg("1"));
        store.append(msg("2"));

        List<MqttHistoryEntry> loaded = new MqttHistoryStore(dir.resolve("mqtt-history.log")).load();

        assertEquals(List.of("1", "2"), loaded.stream().map(MqttHistoryEntry::payload).toList());
    }

    @Test
    @DisplayName("the parent directory is created on first append")
    void createsParentDirectory() {
        Path nested = dir.resolve("fresh").resolve(".nexuslink").resolve("mqtt-history.log");

        assertTrue(new MqttHistoryStore(nested).append(msg("1")));
        assertTrue(Files.exists(nested));
    }

    @Test
    @DisplayName("a missing file loads as empty history")
    void missingFileLoadsEmpty() {
        MqttHistoryStore store = new MqttHistoryStore(dir.resolve("never-written.log"));

        assertTrue(store.load().isEmpty());
        assertNull(store.lastError(), "an absent file is not an error");
    }

    @Test
    @DisplayName("load trims the file down to maxEntries, keeping the newest")
    void loadCompactsOverflow() throws Exception {
        Path file = dir.resolve("mqtt-history.log");
        MqttHistoryStore writer = new MqttHistoryStore(file, 3);
        for (int i = 1; i <= 5; i++) writer.append(msg(String.valueOf(i)));

        List<MqttHistoryEntry> loaded = writer.load();

        assertEquals(List.of("3", "4", "5"), loaded.stream().map(MqttHistoryEntry::payload).toList());
        assertEquals(3, Files.readAllLines(file).size(), "the file itself is compacted, not just the result");
    }

    @Test
    @DisplayName("damaged lines are skipped and the file is rewritten without them")
    void skipsAndDropsDamagedLines() throws Exception {
        Path file = dir.resolve("mqtt-history.log");
        Files.writeString(file, msg("good").encode() + "\n"
                + "<<< truncated garbage\n"
                + msg("also-good").encode() + "\n", StandardCharsets.UTF_8);

        List<MqttHistoryEntry> loaded = new MqttHistoryStore(file).load();

        assertEquals(List.of("good", "also-good"), loaded.stream().map(MqttHistoryEntry::payload).toList());
        assertEquals(2, Files.readAllLines(file).size());
    }

    @Test
    @DisplayName("a multi-line payload does not split into several entries")
    void multilinePayloadStaysOneEntry() {
        MqttHistoryStore store = new MqttHistoryStore(dir.resolve("mqtt-history.log"));
        store.append(new MqttHistoryEntry(1, MqttHistoryEntry.Direction.PUBLISHED, "a", 1, true,
                "{\n  \"k\": \"v\"\n}"));

        List<MqttHistoryEntry> loaded = store.load();

        assertEquals(1, loaded.size());
        assertEquals("{\n  \"k\": \"v\"\n}", loaded.get(0).payload());
        assertTrue(loaded.get(0).retained());
        assertEquals(1, loaded.get(0).qos());
    }

    @Test
    @DisplayName("rewrite replaces the file wholesale")
    void rewrite() {
        MqttHistoryStore store = new MqttHistoryStore(dir.resolve("mqtt-history.log"));
        store.append(msg("old"));

        store.rewrite(List.of(msg("new")));

        assertEquals(List.of("new"), store.load().stream().map(MqttHistoryEntry::payload).toList());
    }

    @Test
    @DisplayName("clear deletes the file and is safe when there is nothing to delete")
    void clear() {
        MqttHistoryStore store = new MqttHistoryStore(dir.resolve("mqtt-history.log"));
        store.append(msg("1"));

        assertTrue(store.clear());
        assertTrue(store.load().isEmpty());
        assertTrue(store.clear(), "clearing an already-absent file is not a failure");
    }

    @Test
    @DisplayName("an unwritable path is reported, not thrown — history never breaks the client")
    void ioFailureIsReportedNotThrown() {
        // The history file's parent is an existing regular file, so createDirectories must fail.
        Path blocker = dir.resolve("blocker");
        assertDoesNotThrow(() -> Files.writeString(blocker, "not a directory"));
        MqttHistoryStore store = new MqttHistoryStore(blocker.resolve("mqtt-history.log"));

        assertFalse(store.append(msg("1")));
        assertNotNull(store.lastError());
        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("maxEntries must be positive")
    void guards() {
        assertThrows(IllegalArgumentException.class,
                () -> new MqttHistoryStore(dir.resolve("h.log"), 0));
    }
}
