package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.MessageFilter.Record;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageFilterTest {

    private static Record rec(int partition, long offset, long ts, String key, String value) {
        return new Record(partition, offset, ts, key, value, Map.of());
    }

    private static Record rec(int partition, long offset, long ts, String key, String value,
                              Map<String, String> headers) {
        return new Record(partition, offset, ts, key, value, headers);
    }

    @Test
    void emptyFilterMatchesEverything() {
        MessageFilter f = MessageFilter.builder().build();
        assertTrue(f.matches(rec(0, 0, 0, null, null)));
        assertTrue(f.matches(rec(7, 999, 123456789L, "k", "v")));
    }

    @Test
    void offsetBoundsAreInclusive() {
        MessageFilter f = MessageFilter.builder().minOffset(10).maxOffset(20).build();
        assertFalse(f.matches(rec(0, 9, 0, "k", "v")));
        assertTrue(f.matches(rec(0, 10, 0, "k", "v")));   // lower boundary inclusive
        assertTrue(f.matches(rec(0, 15, 0, "k", "v")));
        assertTrue(f.matches(rec(0, 20, 0, "k", "v")));   // upper boundary inclusive
        assertFalse(f.matches(rec(0, 21, 0, "k", "v")));
    }

    @Test
    void timestampRangeFilters() {
        MessageFilter f = MessageFilter.builder().minTimestamp(1000).maxTimestamp(2000).build();
        assertFalse(f.matches(rec(0, 0, 999, "k", "v")));
        assertTrue(f.matches(rec(0, 0, 1000, "k", "v")));
        assertTrue(f.matches(rec(0, 0, 2000, "k", "v")));
        assertFalse(f.matches(rec(0, 0, 2001, "k", "v")));
    }

    @Test
    void partitionEquality() {
        MessageFilter f = MessageFilter.builder().partition(3).build();
        assertTrue(f.matches(rec(3, 0, 0, "k", "v")));
        assertFalse(f.matches(rec(2, 0, 0, "k", "v")));
    }

    @Test
    void keySubstringRespectsCaseSensitivity() {
        MessageFilter insensitive = MessageFilter.builder().keyContains("USER", false).build();
        assertTrue(insensitive.matches(rec(0, 0, 0, "order-user-42", "v")));

        MessageFilter sensitive = MessageFilter.builder().keyContains("USER", true).build();
        assertFalse(sensitive.matches(rec(0, 0, 0, "order-user-42", "v")));
        assertTrue(sensitive.matches(rec(0, 0, 0, "order-USER-42", "v")));
    }

    @Test
    void valueRegexMatchAndNonMatch() {
        MessageFilter f = MessageFilter.builder().valueMatches("^\\{.*\\}$", true).build();
        assertTrue(f.matches(rec(0, 0, 0, "k", "{\"id\":1}")));
        assertFalse(f.matches(rec(0, 0, 0, "k", "plain text")));
    }

    @Test
    void invalidRegexFailsFastAtBuildTime() {
        assertThrows(java.util.regex.PatternSyntaxException.class,
                () -> MessageFilter.builder().valueMatches("[unclosed", true));
    }

    @Test
    void headerPresenceAndValueMatch() {
        Map<String, String> headers = Map.of("source", "billing", "trace", "abc123");

        MessageFilter present = MessageFilter.builder().headerPresent("source").build();
        assertTrue(present.matches(rec(0, 0, 0, "k", "v", headers)));
        assertFalse(present.matches(rec(0, 0, 0, "k", "v", Map.of("other", "x"))));

        MessageFilter equals = MessageFilter.builder().headerEquals("source", "billing").build();
        assertTrue(equals.matches(rec(0, 0, 0, "k", "v", headers)));
        assertFalse(equals.matches(rec(0, 0, 0, "k", "v", Map.of("source", "shipping"))));

        MessageFilter contains = MessageFilter.builder().headerContains("trace", "abc").build();
        assertTrue(contains.matches(rec(0, 0, 0, "k", "v", headers)));
    }

    @Test
    void nullKeyAndValueDoNotMatchSetPredicatesWithoutNpe() {
        MessageFilter keyFilter = MessageFilter.builder().keyContains("x", false).build();
        assertFalse(keyFilter.matches(rec(0, 0, 0, null, "value")));

        MessageFilter valueFilter = MessageFilter.builder().valueMatches("\\d+", true).build();
        assertFalse(valueFilter.matches(rec(0, 0, 0, "key", null)));
    }

    @Test
    void applyPreservesOrderAndFilters() {
        List<Record> input = List.of(
                rec(0, 1, 0, "a", "v"),
                rec(0, 2, 0, "b", "v"),
                rec(0, 3, 0, "c", "v"),
                rec(0, 4, 0, "d", "v"));
        MessageFilter f = MessageFilter.builder().minOffset(2).maxOffset(3).build();
        List<Record> out = f.apply(input);
        assertEquals(2, out.size());
        assertEquals(2, out.get(0).offset());
        assertEquals(3, out.get(1).offset());
    }

    @Test
    void multiplePredicatesAreAndCombined() {
        MessageFilter f = MessageFilter.builder()
                .partition(1)
                .minOffset(100)
                .keyContains("session", false)
                .build();
        assertTrue(f.matches(rec(1, 150, 0, "user-session-9", "v")));
        assertFalse(f.matches(rec(0, 150, 0, "user-session-9", "v"))); // wrong partition
        assertFalse(f.matches(rec(1, 50, 0, "user-session-9", "v")));  // offset too low
        assertFalse(f.matches(rec(1, 150, 0, "user-9", "v")));         // key mismatch
    }
}
