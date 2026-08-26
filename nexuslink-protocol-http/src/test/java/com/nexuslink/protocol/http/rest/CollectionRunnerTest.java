package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The collection runner, driven against a stub sender so the whole loop is testable offline. */
class CollectionRunnerTest {

    private static RestResponse ok(String body) {
        return new RestResponse(200, "OK", Map.of("X-Token", List.of("hdr-token")), body,
                body.length(), "HTTP/1.1", new RestResponse.Timing(0, 0, 0, 0, 0, 1), false, null);
    }

    private static RestResponse status(int code) {
        return new RestResponse(code, "", Map.of(), "{}", 2, "HTTP/1.1",
                new RestResponse.Timing(0, 0, 0, 0, 0, 1), false, null);
    }

    /** A request whose URL records the variables it was built with. */
    private static RestRequest request(String method, String url) {
        RestRequest r = new RestRequest();
        r.setMethod(method);
        r.setUrl(url);
        return r;
    }

    @Test
    void everyPlannedStepIsSentInOrder() {
        List<String> sent = new ArrayList<>();
        CollectionRunner runner = new CollectionRunner(
                req -> { sent.add(req.getUrl()); return ok("{}"); },
                (id, vars) -> request("GET", "https://api/" + id),
                id -> id);
        RunReport report = runner.run(new RunPlan(List.of("a", "b"), 2, List.of(), false, 0),
                Map.of(), id -> List.of(), null);

        assertEquals(List.of("https://api/a", "https://api/b", "https://api/a", "https://api/b"), sent);
        assertEquals(4, report.steps().size());
        assertTrue(report.isPass());
        assertEquals("4 passed · 0.0 s".substring(0, 8), report.summary().substring(0, 8));
    }

    @Test
    void anExtractedValueIsVisibleToTheNextRequest() {
        List<Map<String, String>> seen = new ArrayList<>();
        CollectionRunner runner = new CollectionRunner(
                req -> ok("{\"token\":\"abc123\"}"),
                (id, vars) -> { seen.add(Map.copyOf(vars)); return request("GET", "https://api/" + id); },
                id -> id);
        runner.run(RunPlan.of(List.of("login", "fetch")), Map.of(),
                id -> id.equals("login")
                        ? List.of(new ResponseExtraction("token",
                                ResponseExtraction.Source.JSON_PATH, "token"))
                        : List.of(),
                null);

        assertFalse(seen.get(0).containsKey("token"), "nothing extracted before the first request");
        assertEquals("abc123", seen.get(1).get("token"), "the second request sees the login's token");
    }

    @Test
    void theReportCollectsEveryExtractedVariable() {
        CollectionRunner runner = new CollectionRunner(req -> ok("{\"id\":7}"),
                (id, vars) -> request("GET", "https://api/" + id), id -> id);
        RunReport report = runner.run(RunPlan.of(List.of("a")), Map.of(),
                id -> List.of(new ResponseExtraction("id", ResponseExtraction.Source.JSON_PATH, "id"),
                        new ResponseExtraction("rid", ResponseExtraction.Source.HEADER, "X-Token")),
                null);
        assertEquals(Map.of("id", "7", "rid", "hdr-token"), report.variables());
    }

    @Test
    void anExtractionThatFindsNothingIsNotedButDoesNotFailTheStep() {
        CollectionRunner runner = new CollectionRunner(req -> ok("{}"),
                (id, vars) -> request("GET", "https://api/x"), id -> id);
        RunReport report = runner.run(RunPlan.of(List.of("a")), Map.of(),
                id -> List.of(new ResponseExtraction("token",
                        ResponseExtraction.Source.JSON_PATH, "data.token")), null);
        assertTrue(report.steps().get(0).passed());
        assertTrue(report.steps().get(0).detail().contains("no value for token"),
                report.steps().get(0).detail());
    }

    @Test
    void aFailedAssertionFailsTheStep() {
        RestRequest checked = request("GET", "https://api/x");
        checked.getAssertions().add(new AssertionSpec(
                ResponseAssertions.Type.STATUS_EQUALS, "", "200", ""));
        CollectionRunner runner = new CollectionRunner(req -> status(500),
                (id, vars) -> checked, id -> id);
        RunReport report = runner.run(RunPlan.of(List.of("a")), Map.of(), id -> List.of(), null);

        assertFalse(report.isPass());
        assertEquals(1, report.failed());
        assertTrue(report.steps().get(0).detail().contains("0/1 passed"), report.steps().get(0).detail());
    }

    @Test
    void aTransportFailureFailsTheStepWithItsMessage() {
        CollectionRunner runner = new CollectionRunner(
                req -> RestResponse.error("connection refused", 3),
                (id, vars) -> request("GET", "https://api/x"), id -> id);
        RunReport report = runner.run(RunPlan.of(List.of("a")), Map.of(), id -> List.of(), null);
        assertFalse(report.steps().get(0).passed());
        assertTrue(report.steps().get(0).detail().contains("connection refused"));
    }

    @Test
    void stopOnFailureEndsTheRunEarly() {
        AtomicInteger sent = new AtomicInteger();
        CollectionRunner runner = new CollectionRunner(
                req -> { sent.incrementAndGet(); return RestResponse.error("boom", 1); },
                (id, vars) -> request("GET", "https://api/" + id), id -> id);
        RunReport report = runner.run(new RunPlan(List.of("a", "b", "c"), 1, List.of(), true, 0),
                Map.of(), id -> List.of(), null);
        assertEquals(1, sent.get(), "the run stops at the first failure");
        assertTrue(report.stoppedEarly());
        assertFalse(report.isPass());
    }

    @Test
    void withoutStopOnFailureTheRunContinues() {
        AtomicInteger sent = new AtomicInteger();
        CollectionRunner runner = new CollectionRunner(
                req -> { sent.incrementAndGet(); return RestResponse.error("boom", 1); },
                (id, vars) -> request("GET", "https://api/" + id), id -> id);
        RunReport report = runner.run(new RunPlan(List.of("a", "b", "c"), 1, List.of(), false, 0),
                Map.of(), id -> List.of(), null);
        assertEquals(3, sent.get());
        assertEquals(3, report.failed());
        assertFalse(report.stoppedEarly());
    }

    @Test
    void aDeletedRequestIsReportedRatherThanSkippedSilently() {
        CollectionRunner runner = new CollectionRunner(req -> ok("{}"), (id, vars) -> null, id -> "Gone");
        RunReport report = runner.run(RunPlan.of(List.of("ghost")), Map.of(), id -> List.of(), null);
        assertEquals(1, report.steps().size());
        assertFalse(report.steps().get(0).passed());
        assertEquals("request no longer exists", report.steps().get(0).detail());
    }

    @Test
    void cancellingStopsTheRun() {
        AtomicInteger sent = new AtomicInteger();
        CollectionRunner[] holder = new CollectionRunner[1];
        holder[0] = new CollectionRunner(req -> {
            if (sent.incrementAndGet() == 2) holder[0].cancel();
            return ok("{}");
        }, (id, vars) -> request("GET", "https://api/" + id), id -> id);
        RunReport report = holder[0].run(new RunPlan(List.of("a"), 5, List.of(), false, 0),
                Map.of(), id -> List.of(), null);
        assertEquals(2, sent.get());
        assertTrue(report.stoppedEarly());
    }

    @Test
    void dataRowsAreVisibleToTheRequestButExtractedValuesWin() {
        List<Map<String, String>> seen = new ArrayList<>();
        CollectionRunner runner = new CollectionRunner(req -> ok("{\"user\":\"extracted\"}"),
                (id, vars) -> { seen.add(Map.copyOf(vars)); return request("GET", "https://api/x"); },
                id -> id);
        runner.run(new RunPlan(List.of("a"), 1,
                        List.of(Map.of("user", "row1"), Map.of("user", "row2")), false, 0),
                Map.of(),
                id -> List.of(new ResponseExtraction("user", ResponseExtraction.Source.JSON_PATH, "user")),
                null);
        assertEquals("row1", seen.get(0).get("user"));
        assertEquals("extracted", seen.get(1).get("user"),
                "a value extracted at runtime is more specific than the data column");
    }

    @Test
    void everyStepIsReportedAsItCompletes() {
        List<Integer> progress = new ArrayList<>();
        CollectionRunner runner = new CollectionRunner(req -> ok("{}"),
                (id, vars) -> request("GET", "https://api/x"), id -> id);
        runner.run(new RunPlan(List.of("a", "b"), 1, List.of(), false, 0), Map.of(),
                id -> List.of(), step -> progress.add(step.index()));
        assertEquals(List.of(0, 1), progress);
    }

    @Test
    void substitutionLeavesUnknownVariablesVisible() {
        var substitute = CollectionRunner.substitution(Map.of("host", "api.test"));
        assertEquals("https://api.test/v1", substitute.apply("https://${host}/v1"));
        assertEquals("https://${missing}/v1", substitute.apply("https://${missing}/v1"));
    }
}
