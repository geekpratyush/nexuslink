package com.nexuslink.protocol.mqtt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MqttMessageHistoryTest {

    private static MqttHistoryEntry msg(String topic, String payload) {
        return new MqttHistoryEntry(0, MqttHistoryEntry.Direction.RECEIVED, topic, 0, false, payload);
    }

    @Test
    @DisplayName("entries come back oldest-first")
    void ordering() {
        MqttMessageHistory h = new MqttMessageHistory();
        h.add(msg("a", "1"));
        h.add(msg("b", "2"));

        assertEquals(List.of("1", "2"), h.entries().stream().map(MqttHistoryEntry::payload).toList());
    }

    @Test
    @DisplayName("the oldest entry is evicted once the capacity is reached")
    void boundedByCapacity() {
        MqttMessageHistory h = new MqttMessageHistory(2);
        h.add(msg("a", "1"));
        h.add(msg("a", "2"));

        MqttHistoryEntry evicted = h.add(msg("a", "3"));

        assertEquals("1", evicted.payload());
        assertEquals(2, h.size());
        assertEquals(List.of("2", "3"), h.entries().stream().map(MqttHistoryEntry::payload).toList());
    }

    @Test
    @DisplayName("addAll of an over-capacity batch keeps only the newest")
    void addAllTrims() {
        MqttMessageHistory h = new MqttMessageHistory(2);

        h.addAll(List.of(msg("a", "1"), msg("a", "2"), msg("a", "3")));

        assertEquals(List.of("2", "3"), h.entries().stream().map(MqttHistoryEntry::payload).toList());
    }

    @Test
    @DisplayName("recent(n) returns the newest n, oldest-first, and never over-reads")
    void recent() {
        MqttMessageHistory h = new MqttMessageHistory();
        h.addAll(List.of(msg("a", "1"), msg("a", "2"), msg("a", "3")));

        assertEquals(List.of("2", "3"), h.recent(2).stream().map(MqttHistoryEntry::payload).toList());
        assertEquals(3, h.recent(99).size());
    }

    @Test
    @DisplayName("matching() applies real MQTT wildcard semantics")
    void matchingUsesTopicFilter() {
        MqttMessageHistory h = new MqttMessageHistory();
        h.addAll(List.of(msg("sensors/kitchen/temp", "k"),
                msg("sensors/hall/temp", "h"),
                msg("alarms/fire", "f")));

        assertEquals(List.of("k", "h"),
                h.matching("sensors/#").stream().map(MqttHistoryEntry::payload).toList());
        assertEquals(List.of("k"),
                h.matching("sensors/kitchen/+").stream().map(MqttHistoryEntry::payload).toList());
    }

    @Test
    @DisplayName("a blank filter matches everything and an invalid one matches nothing")
    void filterEdges() {
        MqttMessageHistory h = new MqttMessageHistory();
        h.addAll(List.of(msg("a/b", "1"), msg("c/d", "2")));

        assertEquals(2, h.matching("").size());
        assertEquals(2, h.matching(null).size());
        assertTrue(h.matching("a/#/b").isEmpty(), "a half-typed filter yields nothing, not an exception");
    }

    @Test
    @DisplayName("search combines the topic filter with a case-insensitive payload contains")
    void search() {
        MqttMessageHistory h = new MqttMessageHistory();
        h.addAll(List.of(msg("sensors/a", "Temperature HIGH"),
                msg("sensors/b", "temperature low"),
                msg("alarms/a", "temperature high")));

        assertEquals(2, h.search("sensors/#", "temperature").size());
        assertEquals(1, h.search("sensors/#", "HIGH").size());
        assertEquals(2, h.search("sensors/#", "  ").size(), "a blank text skips the payload criterion");
    }

    @Test
    @DisplayName("clear empties the log")
    void clear() {
        MqttMessageHistory h = new MqttMessageHistory();
        h.add(msg("a", "1"));

        h.clear();

        assertTrue(h.isEmpty());
        assertEquals(0, h.size());
    }

    @Test
    @DisplayName("a null entry is ignored and a non-positive capacity is rejected")
    void guards() {
        MqttMessageHistory h = new MqttMessageHistory();

        assertNull(h.add(null));
        assertTrue(h.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new MqttMessageHistory(0));
    }

    @Test
    @DisplayName("concurrent appends from broker threads keep every entry")
    void concurrentAdds() throws Exception {
        MqttMessageHistory h = new MqttMessageHistory(10_000);
        int threads = 4;
        int perThread = 500;
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) h.add(msg("a", "x"));
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) worker.join();

        assertEquals(threads * perThread, h.size());
    }
}
