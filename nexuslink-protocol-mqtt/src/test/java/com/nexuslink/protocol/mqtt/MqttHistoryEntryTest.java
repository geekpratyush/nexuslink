package com.nexuslink.protocol.mqtt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MqttHistoryEntryTest {

    @Test
    @DisplayName("encode → decode round-trips every field")
    void roundTrip() {
        MqttHistoryEntry e = new MqttHistoryEntry(1_700_000_000_123L,
                MqttHistoryEntry.Direction.PUBLISHED, "sensors/temp", 2, true, "23.5C");

        assertEquals(e, MqttHistoryEntry.decode(e.encode()));
    }

    @Test
    @DisplayName("a payload with tabs and newlines still occupies exactly one line")
    void multilinePayloadStaysOnOneLine() {
        MqttHistoryEntry e = MqttHistoryEntry.received("a/b", "col1\tcol2\nsecond line\r\nthird", 1, false);

        String line = e.encode();
        assertFalse(line.contains("\n"), "encoded line must not contain a newline");
        assertFalse(line.contains("\r"), "encoded line must not contain a carriage return");
        assertEquals("col1\tcol2\nsecond line\r\nthird", MqttHistoryEntry.decode(line).payload());
    }

    @Test
    @DisplayName("a payload of literal backslashes survives escaping")
    void backslashPayload() {
        MqttHistoryEntry e = MqttHistoryEntry.received("a", "C:\\path\\to\\n", 0, false);

        assertEquals("C:\\path\\to\\n", MqttHistoryEntry.decode(e.encode()).payload());
    }

    @Test
    @DisplayName("an empty payload round-trips as empty, not null")
    void emptyPayload() {
        MqttHistoryEntry decoded = MqttHistoryEntry.decode(MqttHistoryEntry.received("a", "", 0, false).encode());

        assertEquals("", decoded.payload());
    }

    @Test
    @DisplayName("unknown fields from a newer writer are ignored, not fatal")
    void unknownFieldsIgnored() {
        String line = "ts=42\tdir=RECEIVED\tqos=1\tretained=false\ttopic=a/b\tpayload=hi\tcontentType=text/plain";

        MqttHistoryEntry decoded = MqttHistoryEntry.decode(line);

        assertNotNull(decoded);
        assertEquals("a/b", decoded.topic());
        assertEquals("hi", decoded.payload());
    }

    @Test
    @DisplayName("blank, garbage and topic-less lines decode to null rather than throwing")
    void unparsableLinesAreSkipped() {
        assertNull(MqttHistoryEntry.decode(null));
        assertNull(MqttHistoryEntry.decode("   "));
        assertNull(MqttHistoryEntry.decode("this is not a history line"));
        assertNull(MqttHistoryEntry.decode("ts=1\tdir=RECEIVED\tpayload=orphan"));
    }

    @Test
    @DisplayName("a corrupt timestamp or QoS degrades to a sane value")
    void corruptNumbersDegrade() {
        MqttHistoryEntry decoded =
                MqttHistoryEntry.decode("ts=abc\tdir=RECEIVED\tqos=9\tretained=x\ttopic=a\tpayload=p");

        assertEquals(0, decoded.epochMillis());
        assertEquals(2, decoded.qos(), "an out-of-range QoS is clamped into 0..2");
        assertFalse(decoded.retained());
    }

    @Test
    @DisplayName("the factories stamp the direction and the current time")
    void factories() {
        long before = System.currentTimeMillis();

        MqttHistoryEntry in = MqttHistoryEntry.received("a", "p", 0, false);
        MqttHistoryEntry out = MqttHistoryEntry.published("a", "p", 0, false);

        assertEquals(MqttHistoryEntry.Direction.RECEIVED, in.direction());
        assertEquals(MqttHistoryEntry.Direction.PUBLISHED, out.direction());
        assertTrue(in.epochMillis() >= before);
        assertEquals(in.epochMillis(), in.timestamp().toEpochMilli());
    }

    @Test
    @DisplayName("an out-of-range QoS is rejected at construction")
    void qosValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new MqttHistoryEntry(0, MqttHistoryEntry.Direction.RECEIVED, "a", 3, false, "p"));
        assertThrows(IllegalArgumentException.class,
                () -> new MqttHistoryEntry(0, MqttHistoryEntry.Direction.RECEIVED, "a", -1, false, "p"));
    }
}
