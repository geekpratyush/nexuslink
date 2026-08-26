package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunPlanTest {

    @Test
    void aPlainRunSendsEveryRequestOnce() {
        RunPlan plan = RunPlan.of(List.of("a", "b", "c"));
        assertEquals(3, plan.totalSteps());
        assertEquals(List.of("a", "b", "c"), plan.steps().stream().map(RunPlan.Step::requestId).toList());
        assertEquals(1, plan.steps().get(2).iteration());
    }

    @Test
    void iterationsRepeatTheRequestListInOrder() {
        RunPlan plan = new RunPlan(List.of("a", "b"), 3, List.of(), false, 0);
        assertEquals(6, plan.totalSteps());
        assertEquals(List.of("a", "b", "a", "b", "a", "b"),
                plan.steps().stream().map(RunPlan.Step::requestId).toList());
        assertEquals(List.of(1, 1, 2, 2, 3, 3),
                plan.steps().stream().map(RunPlan.Step::iteration).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5),
                plan.steps().stream().map(RunPlan.Step::index).toList());
    }

    @Test
    void aDataFileDecidesTheIterationCount() {
        List<Map<String, String>> rows = List.of(Map.of("user", "ada"), Map.of("user", "bob"),
                Map.of("user", "cleo"));
        RunPlan plan = new RunPlan(List.of("login"), 1, rows, false, 0);
        assertEquals(3, plan.effectiveIterations(), "3 rows means 3 runs, not the 1 iteration asked for");
        assertEquals(3, plan.totalSteps());
        assertEquals("cleo", plan.steps().get(2).row().get("user"));
    }

    @Test
    void everyStepOfAnIterationSeesTheSameRow() {
        RunPlan plan = new RunPlan(List.of("login", "fetch"), 1,
                List.of(Map.of("user", "ada"), Map.of("user", "bob")), false, 0);
        assertEquals("ada", plan.steps().get(0).row().get("user"));
        assertEquals("ada", plan.steps().get(1).row().get("user"));
        assertEquals("bob", plan.steps().get(2).row().get("user"));
    }

    @Test
    void theIterationCountAndDelayAreAlwaysSane() {
        assertEquals(1, new RunPlan(List.of("a"), 0, List.of(), false, 0).iterations());
        assertEquals(1, new RunPlan(List.of("a"), -5, List.of(), false, 0).iterations());
        assertEquals(0, new RunPlan(List.of("a"), 1, List.of(), false, -100).delayMs());
    }

    @Test
    void anEmptyRequestListPlansNothing() {
        assertEquals(0, RunPlan.of(List.of()).totalSteps());
        assertTrue(RunPlan.of(null).steps().isEmpty());
    }

    @Test
    void aJsonArrayDataFileParsesToRows() {
        List<Map<String, String>> rows = RunPlan.parseData(
                "[{\"user\":\"ada\",\"id\":7},{\"user\":\"bob\",\"id\":8}]");
        assertEquals(2, rows.size());
        assertEquals("ada", rows.get(0).get("user"));
        assertEquals("7", rows.get(0).get("id"), "values come through as text for ${var} substitution");
    }

    @Test
    void aCsvDataFileParsesToRowsKeyedByHeader() {
        List<Map<String, String>> rows = RunPlan.parseData("user,role\nada,admin\nbob,\"user, junior\"\n");
        assertEquals(2, rows.size());
        assertEquals("admin", rows.get(0).get("role"));
        assertEquals("user, junior", rows.get(1).get("role"), "quoted commas survive");
    }

    @Test
    void anEmptyOrHeaderOnlyDataFileDrivesNothing() {
        assertTrue(RunPlan.parseData("").isEmpty());
        assertTrue(RunPlan.parseData(null).isEmpty());
        assertTrue(RunPlan.parseData("user,role\n").isEmpty());
    }

    @Test
    void aBrokenJsonDataFileIsReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RunPlan.parseData("[{\"a\": }]"));
        assertTrue(e.getMessage().contains("JSON array"), e.getMessage());
    }
}
