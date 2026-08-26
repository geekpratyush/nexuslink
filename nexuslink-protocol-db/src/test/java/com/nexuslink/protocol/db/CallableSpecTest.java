package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CallableSpecTest {

    @Test
    void procedureRendersOnePlaceholderPerParameter() {
        CallableSpec spec = CallableSpec.procedure("add_user", List.of(
                CallableSpec.Param.in("name", "ada"),
                CallableSpec.Param.in("role", "admin"),
                CallableSpec.Param.out("new_id", Types.INTEGER, "int")));
        assertEquals("{call add_user(?, ?, ?)}", spec.sql());
    }

    @Test
    void procedureWithNoParametersStillHasParentheses() {
        assertEquals("{call cleanup()}", CallableSpec.procedure("cleanup", List.of()).sql());
    }

    @Test
    void functionRendersTheReturnValueOutsideTheArgumentList() {
        CallableSpec spec = CallableSpec.function("tax", List.of(
                CallableSpec.Param.out("return", Types.NUMERIC, "numeric"),
                CallableSpec.Param.in("amount", "100")));
        assertEquals("{? = call tax(?)}", spec.sql());
    }

    @Test
    void functionRequiresAnOutReturnValueFirst() {
        assertThrows(IllegalArgumentException.class, () -> CallableSpec.function("f", List.of()));
        assertThrows(IllegalArgumentException.class, () -> CallableSpec.function("f",
                List.of(CallableSpec.Param.in("x", "1"))));
    }

    @Test
    void routineNameIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> CallableSpec.procedure("  ", List.of()));
    }

    @Test
    void inputsAndOutputsSplitByDirectionWithInoutInBoth() {
        CallableSpec.Param inout = new CallableSpec.Param("n", CallableSpec.Direction.INOUT,
                Types.INTEGER, "int", "1");
        CallableSpec spec = CallableSpec.procedure("p", List.of(
                CallableSpec.Param.in("a", "x"), inout, CallableSpec.Param.out("b", Types.VARCHAR, "varchar")));
        assertEquals(List.of("a", "n"), spec.inputs().stream().map(CallableSpec.Param::name).toList());
        assertEquals(List.of("n", "b"), spec.outputs().stream().map(CallableSpec.Param::name).toList());
    }

    @Test
    void withValuesFillsInputsPositionallyAndLeavesOutParamsAlone() {
        CallableSpec spec = CallableSpec.procedure("p", List.of(
                CallableSpec.Param.in("a", null),
                CallableSpec.Param.out("b", Types.INTEGER, "int"),
                CallableSpec.Param.in("c", null)));
        CallableSpec filled = spec.withValues(Arrays.asList("1", null));
        assertEquals("1", filled.params().get(0).value());
        assertNull(filled.params().get(1).value());
        assertNull(filled.params().get(2).value());
        assertEquals(spec.sql(), filled.sql());
    }

    @Test
    void directionParsingIsCaseInsensitiveAndDefaultsToIn() {
        assertEquals(CallableSpec.Direction.OUT, CallableSpec.Direction.parse(" out "));
        assertEquals(CallableSpec.Direction.INOUT, CallableSpec.Direction.parse("in out"));
        assertEquals(CallableSpec.Direction.IN, CallableSpec.Direction.parse(null));
        assertEquals(CallableSpec.Direction.IN, CallableSpec.Direction.parse("whatever"));
    }

    @Test
    void directionKnowsWhichWayValuesFlow() {
        assertTrue(CallableSpec.Direction.IN.isInput());
        assertFalse(CallableSpec.Direction.IN.isOutput());
        assertTrue(CallableSpec.Direction.INOUT.isInput());
        assertTrue(CallableSpec.Direction.INOUT.isOutput());
        assertFalse(CallableSpec.Direction.OUT.isInput());
    }

    @Test
    void callResultSummaryNamesRowsAndOutParameters() {
        CallResult r = CallResult.of(java.util.Map.of("id", "7"),
                new QueryResult(true, List.of("a"), List.of(), List.of(List.of("1")), 0, 3, false, null), 0, 3);
        assertEquals("1 row(s) · 1 out param(s) · 3 ms", r.summary());
        assertEquals("Error", CallResult.error("boom", 1).summary());
    }
}
