package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfluentWireFormatTest {

    @Test
    void aFramedPayloadIsRecognisedByItsMagicByte() {
        byte[] framed = ConfluentWireFormat.frame(42, "payload".getBytes(StandardCharsets.UTF_8));
        assertTrue(ConfluentWireFormat.isFramed(framed));
        assertEquals(0, framed[0]);
    }

    @Test
    void plainPayloadsAreNotMistakenForFramedOnes() {
        assertFalse(ConfluentWireFormat.isFramed("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertFalse(ConfluentWireFormat.isFramed(new byte[]{0, 1, 2}), "too short to be a frame");
        assertFalse(ConfluentWireFormat.isFramed(null));
    }

    @Test
    void theSchemaIdAndPayloadComeBackIntact() {
        byte[] framed = ConfluentWireFormat.frame(12345, "{\"n\":1}".getBytes(StandardCharsets.UTF_8));
        ConfluentWireFormat.Framed parsed = ConfluentWireFormat.parse(framed);
        assertNotNull(parsed);
        assertEquals(12345, parsed.schemaId());
        assertEquals(5, parsed.payloadOffset());
        assertEquals("{\"n\":1}", parsed.payloadAsText());
    }

    @Test
    void aLargeSchemaIdSurvivesTheRoundTrip() {
        assertEquals(2_000_000_000,
                ConfluentWireFormat.parse(ConfluentWireFormat.frame(2_000_000_000, new byte[]{1})).schemaId());
    }

    @Test
    void anEmptyPayloadIsStillAValidFrame() {
        ConfluentWireFormat.Framed parsed = ConfluentWireFormat.parse(ConfluentWireFormat.frame(7, new byte[0]));
        assertEquals(7, parsed.schemaId());
        assertEquals(0, parsed.payload().length);
    }

    @Test
    void parsingSomethingUnframedIsNullRatherThanNonsense() {
        assertNull(ConfluentWireFormat.parse("hello there".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void protobufsSingleMessageIndexShorthandIsSkipped() {
        // magic, id=9, then a single 0 byte meaning "message index [0]", then the payload
        byte[] data = new byte[]{0, 0, 0, 0, 9, 0, 'h', 'i'};
        ConfluentWireFormat.Framed parsed = ConfluentWireFormat.parse(data, true);
        assertEquals(9, parsed.schemaId());
        assertEquals(6, parsed.payloadOffset());
        assertEquals("hi", parsed.payloadAsText());
    }

    @Test
    void aLongerProtobufMessageIndexArrayIsSkipped() {
        // count=2 (zigzag 4), indexes 1 (zigzag 2) and 0 (zigzag 0), then the payload
        byte[] data = new byte[]{0, 0, 0, 0, 9, 4, 2, 0, 'o', 'k'};
        ConfluentWireFormat.Framed parsed = ConfluentWireFormat.parse(data, true);
        assertEquals("ok", parsed.payloadAsText());
    }

    @Test
    void avroFramingIgnoresMessageIndexesEntirely() {
        byte[] data = new byte[]{0, 0, 0, 0, 9, 0, 'h', 'i'};
        assertEquals(3, ConfluentWireFormat.parse(data, false).payload().length,
                "without the protobuf header the index byte is part of the payload");
    }

    @Test
    void theDistinctSchemaIdsOfABatchAreListedInOrder() {
        List<byte[]> batch = List.of(
                ConfluentWireFormat.frame(3, new byte[]{1}),
                ConfluentWireFormat.frame(7, new byte[]{1}),
                ConfluentWireFormat.frame(3, new byte[]{1}),
                "plain".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(3, 7), ConfluentWireFormat.schemaIdsOf(batch));
        assertTrue(ConfluentWireFormat.schemaIdsOf(null).isEmpty());
    }
}
