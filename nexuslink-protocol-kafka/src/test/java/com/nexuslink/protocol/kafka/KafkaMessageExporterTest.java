package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.KafkaService.KafkaMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KafkaMessageExporterTest {

    private static KafkaMessage msg(int p, long off, long ts, String k, String v) {
        return new KafkaMessage(p, off, ts, k, v);
    }

    @Test
    void jsonEmptyIsEmptyArray() {
        assertEquals("[]", KafkaMessageExporter.toJson(List.of()));
    }

    @Test
    void jsonRendersFieldsAndEscapes() {
        String json = KafkaMessageExporter.toJson(List.of(
                msg(2, 100, 1700000000000L, "k1", "he said \"hi\"\nbye")));
        assertTrue(json.contains("\"partition\": 2"));
        assertTrue(json.contains("\"offset\": 100"));
        assertTrue(json.contains("\"timestamp\": 1700000000000"));
        assertTrue(json.contains("\"key\": \"k1\""));
        // Quote and newline are escaped inside the JSON string.
        assertTrue(json.contains("\\\"hi\\\""));
        assertTrue(json.contains("\\n"));
    }

    @Test
    void jsonNullKeyAndValueAreJsonNull() {
        String json = KafkaMessageExporter.toJson(List.of(msg(0, 0, 0, null, null)));
        assertTrue(json.contains("\"key\": null"));
        assertTrue(json.contains("\"value\": null"));
    }

    @Test
    void csvHasHeaderAndRow() {
        String csv = KafkaMessageExporter.toCsv(List.of(msg(1, 5, 42, "key", "value")));
        String[] lines = csv.split("\r\n");
        assertEquals("partition,offset,timestamp,key,value", lines[0]);
        assertEquals("1,5,42,key,value", lines[1]);
    }

    @Test
    void csvQuotesFieldsWithCommasQuotesNewlines() {
        String csv = KafkaMessageExporter.toCsv(List.of(
                msg(0, 0, 0, "a,b", "line1\nline2 \"q\"")));
        String[] lines = csv.split("\r\n", 2);
        assertTrue(lines[1].contains("\"a,b\""), lines[1]);
        assertTrue(lines[1].contains("\"line1\nline2 \"\"q\"\"\""), lines[1]);
    }

    @Test
    void csvNullFieldsAreEmpty() {
        String csv = KafkaMessageExporter.toCsv(List.of(msg(3, 9, 1, null, null)));
        String[] lines = csv.split("\r\n");
        assertEquals("3,9,1,,", lines[1]);
    }
}
