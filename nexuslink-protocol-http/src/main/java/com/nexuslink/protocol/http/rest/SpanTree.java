package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Arranges a flat list of captured {@link ZipkinSpanExporter.Span}s into a parent→child forest for a
 * trace-tree view (§9.2). Spans are grouped by {@code traceId}; within a trace, a span whose
 * {@code parentId} matches another span's {@code id} becomes that span's child, otherwise it is a root
 * of its trace. Roots and children are ordered by start timestamp. Pure and JavaFX-free so the tree
 * shape is fully unit-testable.
 */
public final class SpanTree {

    /** A node in the trace forest: the span plus its (possibly empty) ordered children. */
    public record Node(ZipkinSpanExporter.Span span, List<Node> children) {
        public String traceId() { return span.traceId(); }
        public long timestampMicros() { return span.timestampMicros(); }
    }

    private SpanTree() {}

    /**
     * Builds the forest. Roots are returned grouped by trace (each trace's roots are contiguous),
     * traces ordered by their earliest span, roots/children ordered by start time.
     */
    public static List<Node> build(List<ZipkinSpanExporter.Span> spans) {
        List<Node> roots = new ArrayList<>();
        if (spans == null || spans.isEmpty()) return roots;

        // Partition by traceId, preserving first-seen order.
        Map<String, List<ZipkinSpanExporter.Span>> byTrace = new LinkedHashMap<>();
        for (var s : spans) {
            byTrace.computeIfAbsent(s.traceId() == null ? "" : s.traceId(), k -> new ArrayList<>()).add(s);
        }

        List<List<Node>> traces = new ArrayList<>();
        for (var traceSpans : byTrace.values()) {
            traces.add(buildOneTrace(traceSpans));
        }
        // Order whole traces by their earliest root timestamp for a stable top-level ordering.
        traces.sort(Comparator.comparingLong(SpanTree::earliest));
        for (var traceRoots : traces) roots.addAll(traceRoots);
        return roots;
    }

    private static List<Node> buildOneTrace(List<ZipkinSpanExporter.Span> spans) {
        // Index nodes by span id so children can attach to their parent.
        Map<String, Node> byId = new LinkedHashMap<>();
        for (var s : spans) {
            // Guard against duplicate ids — first wins (matches the merge-friendly Zipkin model).
            byId.putIfAbsent(s.id(), new Node(s, new ArrayList<>()));
        }
        List<Node> roots = new ArrayList<>();
        for (var node : byId.values()) {
            String parentId = node.span().parentId();
            Node parent = (parentId == null || parentId.isBlank()) ? null : byId.get(parentId);
            if (parent != null && parent != node) {
                parent.children().add(node);
            } else {
                roots.add(node); // no parent, blank parent, or dangling parent → a root
            }
        }
        sortByTime(roots);
        for (var node : byId.values()) sortByTime(node.children());
        return roots;
    }

    private static void sortByTime(List<Node> nodes) {
        nodes.sort(Comparator.comparingLong(Node::timestampMicros));
    }

    private static long earliest(List<Node> roots) {
        long min = Long.MAX_VALUE;
        for (var r : roots) min = Math.min(min, r.timestampMicros());
        return min == Long.MAX_VALUE ? 0 : min;
    }
}
