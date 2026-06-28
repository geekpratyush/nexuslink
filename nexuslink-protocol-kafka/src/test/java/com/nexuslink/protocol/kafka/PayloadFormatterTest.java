package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.PayloadFormatter.Format;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadFormatterTest {

    @Test
    void stringPassesThroughUnchanged() {
        assertEquals("hello world", PayloadFormatter.format("hello world", Format.STRING));
    }

    @Test
    void nullIsEmptyForEveryFormat() {
        assertEquals("", PayloadFormatter.format(null, Format.STRING));
        assertEquals("", PayloadFormatter.format(null, Format.JSON));
        assertEquals("", PayloadFormatter.format(null, Format.HEX));
        assertEquals("", PayloadFormatter.format(null, Format.BASE64));
    }

    @Test
    void jsonPrettyPrintsValidObject() {
        String pretty = PayloadFormatter.format("{\"a\":1,\"b\":[2,3]}", Format.JSON);
        String expected = "{\n  \"a\": 1,\n  \"b\": [\n    2,\n    3\n  ]\n}";
        assertEquals(expected, pretty);
    }

    @Test
    void jsonPreservesStringValuesAndNestedObjects() {
        String pretty = PayloadFormatter.prettyJson("{\"name\":\"ada\",\"ok\":true,\"x\":null}");
        assertTrue(pretty.contains("\"name\": \"ada\""), pretty);
        assertTrue(pretty.contains("\"ok\": true"), pretty);
        assertTrue(pretty.contains("\"x\": null"), pretty);
        assertTrue(pretty.contains("\n"), "should be multi-line: " + pretty);
    }

    @Test
    void jsonEmptyContainersStayCompact() {
        assertEquals("{}", PayloadFormatter.prettyJson("{ }"));
        assertEquals("[]", PayloadFormatter.prettyJson("[ ]"));
    }

    @Test
    void jsonPassesThroughNonJsonUnchanged() {
        assertEquals("not json at all", PayloadFormatter.format("not json at all", Format.JSON));
        assertEquals("{broken", PayloadFormatter.format("{broken", Format.JSON));
        assertEquals("{\"a\":}", PayloadFormatter.prettyJson("{\"a\":}"));
    }

    @Test
    void hexOfKnownString() {
        // "AB" → 0x41 0x42
        assertEquals("41 42", PayloadFormatter.format("AB", Format.HEX));
        assertEquals("", PayloadFormatter.format("", Format.HEX));
    }

    @Test
    void base64OfKnownString() {
        assertEquals("aGVsbG8=", PayloadFormatter.format("hello", Format.BASE64));
        assertEquals("", PayloadFormatter.format("", Format.BASE64));
    }

    @Test
    void formatNeverThrowsOnNullFormat() {
        assertEquals("raw", PayloadFormatter.format("raw", null));
    }
}
