package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ZipkinSpanExporterTest {

    private static ZipkinSpanExporter.Span clientSpan() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("http.method", "GET");
        tags.put("http.status_code", "200");
        return new ZipkinSpanExporter.Span(
                "5b8aa5a2d2c872e8321cf37308d69df2", "e457b5a2e4d86bd1", null,
                "GET /users", ZipkinSpanExporter.Kind.CLIENT,
                1_720_000_000_000_000L, 12_345L, "nexuslink", tags);
    }

    @Test
    void rendersCoreFieldsAndKind() {
        String json = ZipkinSpanExporter.toJson(clientSpan());
        assertTrue(json.contains("\"traceId\":\"5b8aa5a2d2c872e8321cf37308d69df2\""));
        assertTrue(json.contains("\"id\":\"e457b5a2e4d86bd1\""));
        assertTrue(json.contains("\"name\":\"GET /users\""));
        assertTrue(json.contains("\"kind\":\"CLIENT\""));
        assertTrue(json.contains("\"timestamp\":1720000000000000"));
        assertTrue(json.contains("\"duration\":12345"));
        assertTrue(json.contains("\"localEndpoint\":{\"serviceName\":\"nexuslink\"}"));
    }

    @Test
    void tagsAreSortedForStableOutput() {
        String json = ZipkinSpanExporter.toJson(clientSpan());
        assertTrue(json.contains("\"tags\":{\"http.method\":\"GET\",\"http.status_code\":\"200\"}"), json);
    }

    @Test
    void rootSpanOmitsParentId() {
        assertFalse(ZipkinSpanExporter.toJson(clientSpan()).contains("parentId"));
    }

    @Test
    void childSpanIncludesParentId() {
        ZipkinSpanExporter.Span s = new ZipkinSpanExporter.Span(
                "abc", "def", "0123456789abcdef", "child", ZipkinSpanExporter.Kind.NONE,
                1, 1, null, Map.of());
        String json = ZipkinSpanExporter.toJson(s);
        assertTrue(json.contains("\"parentId\":\"0123456789abcdef\""));
        assertFalse(json.contains("\"kind\""), "NONE kind is omitted");
        assertFalse(json.contains("localEndpoint"), "blank service omitted");
        assertFalse(json.contains("\"tags\""), "empty tags omitted");
    }

    @Test
    void durationIsClampedToAtLeastOne() {
        ZipkinSpanExporter.Span s = new ZipkinSpanExporter.Span(
                "a", "b", null, "n", ZipkinSpanExporter.Kind.CLIENT, 5, 0, null, null);
        assertTrue(ZipkinSpanExporter.toJson(s).contains("\"duration\":1"));
    }

    @Test
    void arrayRendersEachSpan() {
        String json = ZipkinSpanExporter.toJsonArray(List.of(clientSpan(), clientSpan()));
        assertTrue(json.startsWith("["));
        assertTrue(json.trim().endsWith("]"));
        assertEquals(2, json.split("\"traceId\"", -1).length - 1);
    }

    @Test
    void emptyArrayIsBrackets() {
        assertEquals("[]", ZipkinSpanExporter.toJsonArray(List.of()));
    }

    @Test
    void specialCharactersInNameAreEscaped() {
        ZipkinSpanExporter.Span s = new ZipkinSpanExporter.Span(
                "a", "b", null, "GET /a\"b\\c", ZipkinSpanExporter.Kind.CLIENT, 1, 1, null, null);
        assertTrue(ZipkinSpanExporter.toJson(s).contains("GET /a\\\"b\\\\c"));
    }
}
