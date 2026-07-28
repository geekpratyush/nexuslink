package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisMessageLogTest {

    private static RedisMessageLog.Entry entry(String channel, String payload) {
        return new RedisMessageLog.Entry(0, channel, null, payload);
    }

    @Test
    @DisplayName("entries come back oldest-first")
    void ordering() {
        RedisMessageLog log = new RedisMessageLog();
        log.add(entry("a", "1"));
        log.add(entry("b", "2"));

        assertEquals(List.of("1", "2"), log.entries().stream().map(RedisMessageLog.Entry::payload).toList());
    }

    @Test
    @DisplayName("the oldest entry is evicted at capacity")
    void bounded() {
        RedisMessageLog log = new RedisMessageLog(2);
        log.add(entry("a", "1"));
        log.add(entry("a", "2"));

        RedisMessageLog.Entry evicted = log.add(entry("a", "3"));

        assertEquals("1", evicted.payload());
        assertEquals(List.of("2", "3"), log.entries().stream().map(RedisMessageLog.Entry::payload).toList());
    }

    @Test
    @DisplayName("a delivered RedisMessage keeps its pattern and channel")
    void fromRedisMessage() {
        RedisMessageLog log = new RedisMessageLog();
        log.add(new RedisMessage("news.sport", "news.*", "goal"));
        RedisMessageLog.Entry e = log.entries().get(0);

        assertEquals("news.sport", e.channel());
        assertEquals("news.*", e.pattern());
        assertEquals("goal", e.payload());
        assertTrue(e.isPattern());
        assertTrue(e.epochMillis() > 0, "an arriving message is stamped");
    }

    @Test
    @DisplayName("a plain subscription message has no pattern")
    void plainMessageHasNoPattern() {
        RedisMessageLog log = new RedisMessageLog();
        log.add(new RedisMessage("chat", null, "hi"));

        assertFalse(log.entries().get(0).isPattern());
    }

    @Test
    @DisplayName("matching() uses Redis glob semantics on the channel")
    void matchingUsesRedisGlob() {
        RedisMessageLog log = new RedisMessageLog();
        log.add(entry("news.sport", "a"));
        log.add(entry("news.tech", "b"));
        log.add(entry("alerts.fire", "c"));

        assertEquals(List.of("a", "b"),
                log.matching("news.*").stream().map(RedisMessageLog.Entry::payload).toList());
        assertEquals(3, log.matching("").size(), "a blank glob matches everything");
        assertEquals(3, log.matching(null).size());
    }

    @Test
    @DisplayName("search combines the channel glob with a case-insensitive payload contains")
    void search() {
        RedisMessageLog log = new RedisMessageLog();
        log.add(entry("news.sport", "GOAL scored"));
        log.add(entry("news.tech", "release"));
        log.add(entry("alerts.fire", "goal"));

        assertEquals(1, log.search("news.*", "goal").size());
        assertEquals(2, log.search("news.*", "").size());
        assertEquals(2, log.search("", "goal").size());
    }

    @Test
    @DisplayName("clear empties the log and a null add is ignored")
    void clearAndGuards() {
        RedisMessageLog log = new RedisMessageLog();
        log.add(entry("a", "1"));

        assertNull(log.add((RedisMessageLog.Entry) null));
        assertNull(log.add((RedisMessage) null));
        log.clear();

        assertTrue(log.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RedisMessageLog(0));
    }

    @Test
    @DisplayName("concurrent deliveries from the reader thread keep every message")
    void concurrentAdds() throws Exception {
        RedisMessageLog log = new RedisMessageLog(10_000);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < 500; i++) log.add(entry("c", "x"));
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) worker.join();

        assertEquals(2_000, log.size());
    }
}
