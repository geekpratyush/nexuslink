package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseAssertionsTest {

    private static RestResponse response(int status, Map<String, List<String>> headers, String body) {
        return new RestResponse(status, "", headers, body,
                body == null ? 0 : body.length(), "HTTP/1.1",
                new RestResponse.Timing(0, 0, 0, 0, 0, 0), false, null);
    }

    @Test
    void statusEqualsPassesAndFails() {
        RestResponse ok = response(200, Map.of(), "");
        assertTrue(new ResponseAssertions().statusEquals(200).evaluate(ok).allPassed());
        assertFalse(new ResponseAssertions().statusEquals(404).evaluate(ok).allPassed());
    }

    @Test
    void statusInRangeBoundaries() {
        ResponseAssertions twoXX = new ResponseAssertions().statusInRange(200, 299);
        assertTrue(twoXX.evaluate(response(200, Map.of(), "")).allPassed());
        assertTrue(twoXX.evaluate(response(299, Map.of(), "")).allPassed());
        assertFalse(twoXX.evaluate(response(300, Map.of(), "")).allPassed());
        assertFalse(twoXX.evaluate(response(199, Map.of(), "")).allPassed());
    }

    @Test
    void headerEqualsIsCaseInsensitiveOnName() {
        RestResponse r = response(200,
                Map.of("Content-Type", List.of("application/json")), "");
        assertTrue(new ResponseAssertions()
                .headerEquals("content-type", "application/json")
                .evaluate(r).allPassed());
        assertFalse(new ResponseAssertions()
                .headerEquals("Content-Type", "text/plain")
                .evaluate(r).allPassed());
    }

    @Test
    void headerContainsMatchesSubstring() {
        RestResponse r = response(200,
                Map.of("Content-Type", List.of("application/json; charset=utf-8")), "");
        assertTrue(new ResponseAssertions()
                .headerContains("Content-Type", "json").evaluate(r).allPassed());
        assertFalse(new ResponseAssertions()
                .headerContains("Content-Type", "xml").evaluate(r).allPassed());
    }

    @Test
    void missingHeaderFailsGracefully() {
        RestResponse r = response(200, Map.of(), "");
        ResponseAssertions.Report report = new ResponseAssertions()
                .headerEquals("X-Absent", "v").evaluate(r);
        assertFalse(report.allPassed());
        assertTrue(report.results().get(0).message().contains("absent"));
    }

    @Test
    void bodyContains() {
        RestResponse r = response(200, Map.of(), "hello world");
        assertTrue(new ResponseAssertions().bodyContains("world").evaluate(r).allPassed());
        assertFalse(new ResponseAssertions().bodyContains("mars").evaluate(r).allPassed());
    }

    @Test
    void bodyContainsHandlesNullBody() {
        RestResponse r = response(200, Map.of(), null);
        assertFalse(new ResponseAssertions().bodyContains("x").evaluate(r).allPassed());
    }

    @Test
    void jsonPathEqualsWithDottedPath() {
        RestResponse r = response(200, Map.of(),
                "{\"user\":{\"name\":\"Pratyush\",\"id\":7}}");
        assertTrue(new ResponseAssertions()
                .jsonPathEquals("user.name", "Pratyush").evaluate(r).allPassed());
        assertTrue(new ResponseAssertions()
                .jsonPathEquals("user.id", "7").evaluate(r).allPassed());
        assertFalse(new ResponseAssertions()
                .jsonPathEquals("user.name", "Someone").evaluate(r).allPassed());
    }

    @Test
    void jsonPathEqualsWithPointerAndArrayIndex() {
        RestResponse r = response(200, Map.of(),
                "{\"items\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}");
        assertTrue(new ResponseAssertions()
                .jsonPathEquals("/items/1/sku", "B").evaluate(r).allPassed());
        assertTrue(new ResponseAssertions()
                .jsonPathEquals("items.0.sku", "A").evaluate(r).allPassed());
    }

    @Test
    void jsonPathSupportsDollarPrefix() {
        RestResponse r = response(200, Map.of(), "{\"ok\":true}");
        assertTrue(new ResponseAssertions()
                .jsonPathEquals("$.ok", "true").evaluate(r).allPassed());
    }

    @Test
    void jsonPathMissingNodeFails() {
        RestResponse r = response(200, Map.of(), "{\"a\":1}");
        ResponseAssertions.Report report = new ResponseAssertions()
                .jsonPathEquals("b", "1").evaluate(r);
        assertFalse(report.allPassed());
        assertTrue(report.results().get(0).message().contains("not found"));
    }

    @Test
    void jsonPathInvalidJsonFails() {
        RestResponse r = response(200, Map.of(), "not json{");
        ResponseAssertions.Report report = new ResponseAssertions()
                .jsonPathEquals("a", "1").evaluate(r);
        assertFalse(report.allPassed());
        assertTrue(report.results().get(0).message().contains("invalid JSON"));
    }

    @Test
    void jsonPathEmptyBodyFails() {
        RestResponse r = response(200, Map.of(), "");
        assertFalse(new ResponseAssertions().jsonPathEquals("a", "1").evaluate(r).allPassed());
    }

    @Test
    void reportAggregatesMixedResults() {
        RestResponse r = response(201,
                Map.of("Location", List.of("/items/9")), "{\"id\":9}");
        ResponseAssertions.Report report = new ResponseAssertions()
                .statusEquals(201)                       // pass
                .headerContains("Location", "/items")    // pass
                .jsonPathEquals("id", "9")               // pass
                .bodyContains("nope")                    // fail
                .evaluate(r);

        assertFalse(report.allPassed());
        assertEquals(3, report.passedCount());
        assertEquals(1, report.failedCount());
        assertEquals(4, report.results().size());
        assertEquals("3/4 passed", report.summary());
    }

    @Test
    void allPassedWhenEveryAssertionHolds() {
        RestResponse r = response(200,
                Map.of("Content-Type", List.of("application/json")), "{\"ok\":true}");
        ResponseAssertions.Report report = new ResponseAssertions()
                .statusInRange(200, 299)
                .headerEquals("Content-Type", "application/json")
                .jsonPathEquals("ok", "true")
                .evaluate(r);

        assertTrue(report.allPassed());
        assertEquals("3/3 passed", report.summary());
    }

    @Test
    void emptyAssertionSetPasses() {
        ResponseAssertions.Report report =
                new ResponseAssertions().evaluate(response(500, Map.of(), ""));
        assertTrue(report.allPassed());
        assertEquals(0, report.passedCount());
    }

    @Test
    void assertionLabelsAreReadable() {
        assertEquals("status == 200",
                ResponseAssertions.Assertion.statusEquals(200).label());
        assertEquals("status in [200..299]",
                ResponseAssertions.Assertion.statusInRange(200, 299).label());
        assertEquals("body contains \"x\"",
                ResponseAssertions.Assertion.bodyContains("x").label());
    }

    @Test
    void resultsListIsImmutable() {
        ResponseAssertions.Report report = new ResponseAssertions()
                .statusEquals(200).evaluate(response(200, Map.of(), ""));
        assertThrows(UnsupportedOperationException.class,
                () -> report.results().add(null));
    }
}
