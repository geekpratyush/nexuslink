package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TraceContext} against the W3C Trace Context (Level 1) spec: the canonical
 * traceparent example, rejection of malformed traceparents, tracestate parse/format round-tripping
 * with whitespace and the 32-member cap, child/root generation, and header injection/extraction.
 */
class TraceContextTest {

    private static final String EXAMPLE =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    // ----- traceparent parsing -----

    @Test
    void parsesTheSpecExample() {
        TraceContext ctx = TraceContext.parseTraceparent(EXAMPLE);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.traceId());
        assertEquals("00f067aa0ba902b7", ctx.parentId());
        assertEquals("00f067aa0ba902b7", ctx.spanId());
        assertEquals(0x01, ctx.flags());
        assertTrue(ctx.isSampled());
    }

    @Test
    void roundTripsTheSpecExample() {
        assertEquals(EXAMPLE, TraceContext.parseTraceparent(EXAMPLE).formatTraceparent());
    }

    @Test
    void unsampledFlagIsReadCorrectly() {
        TraceContext ctx = TraceContext.parseTraceparent(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00");
        assertFalse(ctx.isSampled());
        assertEquals(0x00, ctx.flags());
    }

    @Test
    void preservesUnknownFlagBits() {
        TraceContext ctx = TraceContext.parseTraceparent(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ff");
        assertEquals(0xff, ctx.flags());
        assertTrue(ctx.isSampled());
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ff", ctx.formatTraceparent());
    }

    @Test
    void rejectsAllZeroTraceId() {
        assertInvalid("00-00000000000000000000000000000000-00f067aa0ba902b7-01");
    }

    @Test
    void rejectsAllZeroParentId() {
        assertInvalid("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01");
    }

    @Test
    void rejectsUppercaseHex() {
        assertInvalid("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01");
    }

    @Test
    void rejectsWrongTraceIdLength() {
        assertInvalid("00-4bf92f3577b34da6a3ce929d0e0e47-00f067aa0ba902b7-01");
    }

    @Test
    void rejectsWrongParentIdLength() {
        assertInvalid("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902-01");
    }

    @Test
    void rejectsNonHexField() {
        assertInvalid("00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01");
    }

    @Test
    void rejectsTooFewFields() {
        assertInvalid("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7");
    }

    @Test
    void rejectsTrailingDataForVersion00() {
        assertInvalid(EXAMPLE + "-extra");
    }

    @Test
    void rejectsReservedVersionFf() {
        assertInvalid("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void rejectsBadFlagsLength() {
        assertInvalid("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1");
    }

    @Test
    void rejectsNull() {
        assertTrue(TraceContext.tryParseTraceparent(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TraceContext.parseTraceparent(null));
    }

    private static void assertInvalid(String traceparent) {
        assertTrue(TraceContext.tryParseTraceparent(traceparent).isEmpty(),
                () -> "expected invalid: " + traceparent);
        assertThrows(IllegalArgumentException.class, () -> TraceContext.parseTraceparent(traceparent));
    }

    // ----- generators -----

    @Test
    void rootGeneratorProducesReParseableTraceparent() {
        TraceContext root = TraceContext.newRootTraceparent(true);
        assertTrue(root.isSampled());
        String header = root.formatTraceparent();
        TraceContext reparsed = TraceContext.parseTraceparent(header);
        assertEquals(root.traceId(), reparsed.traceId());
        assertEquals(root.parentId(), reparsed.parentId());
        assertEquals(root.flags(), reparsed.flags());
        assertEquals(32, root.traceId().length());
        assertEquals(16, root.parentId().length());
    }

    @Test
    void rootGeneratorHonoursUnsampled() {
        TraceContext root = TraceContext.newRootTraceparent(false);
        assertFalse(root.isSampled());
        assertEquals("00", root.formatTraceparent().substring(root.formatTraceparent().length() - 2));
    }

    @Test
    void rootGeneratorProducesDistinctIds() {
        TraceContext a = TraceContext.newRootTraceparent(true);
        TraceContext b = TraceContext.newRootTraceparent(true);
        assertNotEquals(a.traceId(), b.traceId());
        assertNotEquals(a.parentId(), b.parentId());
    }

    @Test
    void childKeepsTraceIdAndFlagsButChangesParentId() {
        TraceContext child = TraceContext.childOf(EXAMPLE);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", child.traceId());
        assertEquals(0x01, child.flags());
        assertTrue(child.isSampled());
        assertNotEquals("00f067aa0ba902b7", child.parentId());
        assertEquals(16, child.parentId().length());
        // The child is itself a valid traceparent.
        assertTrue(TraceContext.tryParseTraceparent(child.formatTraceparent()).isPresent());
    }

    @Test
    void childOfRejectsInvalidParent() {
        assertThrows(IllegalArgumentException.class, () -> TraceContext.childOf("garbage"));
    }

    @Test
    void instanceChildPreservesTraceState() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("rojo=00f067aa0ba902b7");
        TraceContext ctx = TraceContext.parseTraceparent(EXAMPLE).withTraceState(ts);
        TraceContext child = ctx.child();
        assertEquals(ctx.traceId(), child.traceId());
        assertNotEquals(ctx.parentId(), child.parentId());
        assertEquals("rojo=00f067aa0ba902b7", child.traceState().format());
    }

    // ----- tracestate parse / format -----

    @Test
    void parsesAndPreservesMemberOrder() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE");
        assertEquals(2, ts.size());
        assertEquals("rojo", ts.members().get(0).key());
        assertEquals("congo", ts.members().get(1).key());
        assertEquals("00f067aa0ba902b7,congo=t61rcWkgMzE",
                ts.get("rojo").get() + "," + "congo=" + ts.get("congo").get());
    }

    @Test
    void trimsOptionalWhitespaceAroundMembers() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("  rojo=1 ,\tcongo=2\t");
        assertEquals("rojo=1,congo=2", ts.format());
    }

    @Test
    void ignoresWhitespaceOnlyMembers() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("rojo=1, ,congo=2");
        assertEquals("rojo=1,congo=2", ts.format());
    }

    @Test
    void roundTripsCanonicalForm() {
        String header = "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE";
        assertEquals(header, TraceContext.TraceState.parse(header).format());
    }

    @Test
    void allowsMultiTenantKeys() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("fw529a3039@dt=00f067aa0ba902b7");
        assertEquals(1, ts.size());
        assertEquals("fw529a3039@dt", ts.members().get(0).key());
    }

    @Test
    void dropsListWithInvalidKey() {
        assertTrue(TraceContext.TraceState.parse("BadKey=1").isEmpty());
        assertTrue(TraceContext.TraceState.parse("rojo=1,BAD=2").isEmpty());
    }

    @Test
    void dropsListWithEmptyKey() {
        assertTrue(TraceContext.TraceState.parse("=value").isEmpty());
    }

    @Test
    void dropsListWithInvalidValue() {
        // The value grammar excludes '=' (0x3D); a second '=' inside a member is therefore invalid.
        assertTrue(TraceContext.TraceState.parse("rojo=a=b").isEmpty());
        // A DEL control character (0x7F) is outside the printable value range.
        assertTrue(TraceContext.TraceState.parse("rojo=a\u007Fb").isEmpty());
    }

    @Test
    void dropsListWithDuplicateKeys() {
        assertTrue(TraceContext.TraceState.parse("rojo=1,rojo=2").isEmpty());
    }

    @Test
    void emptyOrNullTraceStateIsEmpty() {
        assertTrue(TraceContext.TraceState.parse(null).isEmpty());
        assertTrue(TraceContext.TraceState.parse("").isEmpty());
        assertTrue(TraceContext.TraceState.parse("   ").isEmpty());
        assertEquals("", TraceContext.TraceState.EMPTY.format());
    }

    @Test
    void dropsListExceedingThirtyTwoMembers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i > 0) sb.append(',');
            sb.append("k").append(i).append("=v").append(i);
        }
        assertTrue(TraceContext.TraceState.parse(sb.toString()).isEmpty());
    }

    @Test
    void parsesExactlyThirtyTwoMembers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if (i > 0) sb.append(',');
            sb.append("k").append(i).append("=v").append(i);
        }
        assertEquals(32, TraceContext.TraceState.parse(sb.toString()).size());
    }

    // ----- tracestate mutation -----

    @Test
    void withAddsNewMemberAtFront() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("congo=t61rcWkgMzE")
                .with("rojo", "00f067aa0ba902b7");
        assertEquals("rojo=00f067aa0ba902b7,congo=t61rcWkgMzE", ts.format());
    }

    @Test
    void withUpdatesExistingMemberAndMovesItToFront() {
        TraceContext.TraceState ts = TraceContext.TraceState.parse("rojo=1,congo=2,foo=3")
                .with("congo", "99");
        assertEquals("congo=99,rojo=1,foo=3", ts.format());
        assertEquals(3, ts.size());
    }

    @Test
    void withEvictsOldestToStayWithinCap() {
        TraceContext.TraceState ts = TraceContext.TraceState.EMPTY;
        for (int i = 0; i < 32; i++) {
            ts = ts.with("k" + i, "v" + i);
        }
        assertEquals(32, ts.size());
        // k0 is the oldest (right-most); adding one more evicts it.
        ts = ts.with("fresh", "new");
        assertEquals(32, ts.size());
        assertEquals("fresh", ts.members().get(0).key());
        assertTrue(ts.get("k0").isEmpty());
        assertTrue(ts.get("k1").isPresent());
    }

    @Test
    void withRejectsInvalidKeyOrValue() {
        TraceContext.TraceState empty = TraceContext.TraceState.EMPTY;
        assertThrows(IllegalArgumentException.class, () -> empty.with("BAD", "1"));
        assertThrows(IllegalArgumentException.class, () -> empty.with("ok", "trailing "));
    }

    @Test
    void withDoesNotMutateOriginal() {
        TraceContext.TraceState original = TraceContext.TraceState.parse("rojo=1");
        original.with("congo", "2");
        assertEquals("rojo=1", original.format());
    }

    // ----- inject / extract -----

    @Test
    void injectsTraceparentOnly() {
        Map<String, String> headers = new LinkedHashMap<>();
        TraceContext.parseTraceparent(EXAMPLE).injectInto(headers);
        assertEquals(EXAMPLE, headers.get(TraceContext.TRACEPARENT));
        assertFalse(headers.containsKey(TraceContext.TRACESTATE));
    }

    @Test
    void injectsTraceparentAndTraceState() {
        Map<String, String> headers = new LinkedHashMap<>();
        TraceContext.TraceState ts = TraceContext.TraceState.parse("rojo=1,congo=2");
        TraceContext.parseTraceparent(EXAMPLE).withTraceState(ts).injectInto(headers);
        assertEquals(EXAMPLE, headers.get(TraceContext.TRACEPARENT));
        assertEquals("rojo=1,congo=2", headers.get(TraceContext.TRACESTATE));
    }

    @Test
    void extractReadsBothHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TraceContext.TRACEPARENT, EXAMPLE);
        headers.put(TraceContext.TRACESTATE, "rojo=1,congo=2");
        Optional<TraceContext> ctx = TraceContext.extract(headers);
        assertTrue(ctx.isPresent());
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.get().traceId());
        assertEquals("rojo=1,congo=2", ctx.get().traceState().format());
    }

    @Test
    void extractReturnsEmptyWhenTraceparentMissingOrInvalid() {
        assertTrue(TraceContext.extract(new LinkedHashMap<>()).isEmpty());
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put(TraceContext.TRACEPARENT, "nope");
        assertTrue(TraceContext.extract(bad).isEmpty());
        assertTrue(TraceContext.extract(null).isEmpty());
    }

    @Test
    void extractDropsInvalidTraceStateButKeepsContext() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(TraceContext.TRACEPARENT, EXAMPLE);
        headers.put(TraceContext.TRACESTATE, "BAD KEY=1");
        Optional<TraceContext> ctx = TraceContext.extract(headers);
        assertTrue(ctx.isPresent());
        assertTrue(ctx.get().traceState().isEmpty());
    }

    @Test
    void memberRecordExposesKeyAndValue() {
        List<TraceContext.TraceState.Member> members =
                TraceContext.TraceState.parse("rojo=1").members();
        assertEquals("rojo", members.get(0).key());
        assertEquals("1", members.get(0).value());
    }
}
