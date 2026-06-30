package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryJsonTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesArrayOfStrings() {
        Object v = SchemaRegistryJson.parse("[\"a\",\"b\",\"c\"]");
        assertEquals(List.of("a", "b", "c"), v);
    }

    @Test
    void parsesEmptyArray() {
        assertEquals(List.of(), SchemaRegistryJson.parse("[]"));
    }

    @Test
    void parsesArrayOfInts() {
        Object v = SchemaRegistryJson.parse("[1, 2, 3]");
        assertEquals(List.of(1L, 2L, 3L), v);
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesObjectWithEmbeddedEscapedSchema() {
        String json = "{\"subject\":\"t\",\"version\":2,\"id\":5,\"schema\":\"{\\\"type\\\":\\\"string\\\"}\"}";
        Map<String, Object> m = (Map<String, Object>) SchemaRegistryJson.parse(json);
        assertEquals("t", m.get("subject"));
        assertEquals(2L, m.get("version"));
        assertEquals(5L, m.get("id"));
        // The schema value must come back as the unescaped JSON document.
        assertEquals("{\"type\":\"string\"}", m.get("schema"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stringEscapeRoundTrip() {
        String value = "line1\nwith \"quotes\" and a \\backslash\\";
        String encoded = SchemaRegistryJson.quote(value);
        Map<String, Object> m = (Map<String, Object>) SchemaRegistryJson.parse("{\"v\":" + encoded + "}");
        assertEquals(value, m.get("v"));
    }

    @Test
    void unicodeEscapeDecodes() {
        assertEquals("Ω", SchemaRegistryJson.parse("\"\\u03a9\""));
    }

    @Test
    void malformedThrows() {
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistryJson.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> SchemaRegistryJson.parse("[1,2"));
    }
}
