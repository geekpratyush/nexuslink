package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BodyFormatterTest {

    @Test
    void prettyJsonIndentsValidJson() {
        String out = BodyFormatter.prettyJson("{\"a\":1,\"b\":[2,3]}");
        assertTrue(out.contains("\n"), "should be multi-line");
        assertTrue(out.contains("\"a\" : 1"));
    }

    @Test
    void prettyJsonReturnsInputWhenNotJson() {
        assertEquals("not json", BodyFormatter.prettyJson("not json"));
    }

    @Test
    void prettyXmlIndentsValidXml() {
        String out = BodyFormatter.prettyXml("<root><a>1</a><b>2</b></root>");
        String[] lines = out.split("\n");
        assertTrue(lines.length >= 4, "expected indented multi-line output, got: " + out);
        assertTrue(out.contains("  <a>1</a>"), "child should be indented: " + out);
    }

    @Test
    void prettyXmlReturnsInputWhenMalformed() {
        String bad = "<root><unclosed>";
        assertEquals(bad, BodyFormatter.prettyXml(bad));
    }

    @Test
    void prettyXmlDoesNotResolveExternalEntities() {
        // A DOCTYPE with an external entity must not be expanded — it should fail
        // closed and return the original text (XXE hardening).
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
        assertEquals(xxe, BodyFormatter.prettyXml(xxe));
    }

    @Test
    void hexDumpFormatsOffsetsAndAscii() {
        String out = BodyFormatter.hexDump("AB".getBytes());
        assertTrue(out.startsWith("00000000  "), out);
        assertTrue(out.contains("41 42"), "hex of 'AB'");
        assertTrue(out.contains("|AB|"), "ascii gutter");
    }

    @Test
    void hexDumpEmpty() {
        assertEquals("(empty body)", BodyFormatter.hexDump(new byte[0]));
    }

    @Test
    void renderModesDispatch() {
        assertEquals("raw text", BodyFormatter.render("raw text", "text/plain", BodyFormatter.Mode.RAW));
        assertTrue(BodyFormatter.render("{\"a\":1}", "application/json", BodyFormatter.Mode.PRETTY).contains("\n"));
        assertTrue(BodyFormatter.render("AB", null, BodyFormatter.Mode.HEX).contains("41 42"));
    }

    @Test
    void sniffsTypeFromContentAndHeader() {
        assertTrue(BodyFormatter.isJson(null, "  {\"x\":1}"));
        assertTrue(BodyFormatter.isXml(null, "  <x/>"));
        assertTrue(BodyFormatter.isJson("application/json; charset=utf-8", "anything"));
        assertFalse(BodyFormatter.isXml("application/json", "<looks-xml/>"));
    }
}
