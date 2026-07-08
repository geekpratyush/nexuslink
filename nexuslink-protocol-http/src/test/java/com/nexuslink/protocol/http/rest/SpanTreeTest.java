package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpanTreeTest {

    private static ZipkinSpanExporter.Span span(String trace, String id, String parent, long ts) {
        return new ZipkinSpanExporter.Span(trace, id, parent, "GET /" + id,
                ZipkinSpanExporter.Kind.CLIENT, ts, 1000, "nexuslink", Map.of());
    }

    @Test
    void emptyInputYieldsNoRoots() {
        assertTrue(SpanTree.build(List.of()).isEmpty());
        assertTrue(SpanTree.build(null).isEmpty());
    }

    @Test
    void singleSpanIsOneRoot() {
        var roots = SpanTree.build(List.of(span("t1", "a", null, 10)));
        assertEquals(1, roots.size());
        assertEquals("a", roots.get(0).span().id());
        assertTrue(roots.get(0).children().isEmpty());
    }

    @Test
    void childAttachesToParent() {
        var roots = SpanTree.build(List.of(
                span("t1", "root", null, 10),
                span("t1", "child", "root", 20)));
        assertEquals(1, roots.size());
        assertEquals("root", roots.get(0).span().id());
        assertEquals(1, roots.get(0).children().size());
        assertEquals("child", roots.get(0).children().get(0).span().id());
    }

    @Test
    void danglingParentBecomesRoot() {
        var roots = SpanTree.build(List.of(span("t1", "orphan", "missing", 10)));
        assertEquals(1, roots.size());
        assertEquals("orphan", roots.get(0).span().id());
    }

    @Test
    void separateTraceIdsAreSeparateRoots() {
        var roots = SpanTree.build(List.of(
                span("t1", "a", null, 30),
                span("t2", "b", null, 10)));
        assertEquals(2, roots.size());
        // trace t2 (earliest ts=10) comes first
        assertEquals("b", roots.get(0).span().id());
        assertEquals("a", roots.get(1).span().id());
    }

    @Test
    void childrenSortedByTimestamp() {
        var roots = SpanTree.build(List.of(
                span("t1", "root", null, 10),
                span("t1", "late", "root", 50),
                span("t1", "early", "root", 20)));
        var kids = roots.get(0).children();
        assertEquals("early", kids.get(0).span().id());
        assertEquals("late", kids.get(1).span().id());
    }

    @Test
    void multiLevelNesting() {
        var roots = SpanTree.build(List.of(
                span("t1", "a", null, 10),
                span("t1", "b", "a", 20),
                span("t1", "c", "b", 30)));
        assertEquals(1, roots.size());
        var b = roots.get(0).children().get(0);
        assertEquals("b", b.span().id());
        assertEquals("c", b.children().get(0).span().id());
    }
}
