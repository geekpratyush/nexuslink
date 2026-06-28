package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssertionSpecTest {

    private static RestResponse response(int status, String body) {
        return new RestResponse(status, "", Map.of("Content-Type", List.of("application/json")),
                body, body.length(), "HTTP/1.1",
                new RestResponse.Timing(0, 0, 0, 0, 0, 1), false, null);
    }

    @Test
    void compilesOnlyEnabledCompleteSpecs() {
        AssertionSpec ok = new AssertionSpec(ResponseAssertions.Type.STATUS_EQUALS, "", "200", "");
        AssertionSpec disabled = new AssertionSpec(ResponseAssertions.Type.BODY_CONTAINS, "", "x", "");
        disabled.setEnabled(false);
        AssertionSpec incomplete = new AssertionSpec(ResponseAssertions.Type.HEADER_EQUALS, "", "v", "");

        ResponseAssertions ra = AssertionSpec.toAssertions(List.of(ok, disabled, incomplete));
        assertEquals(1, ra.assertions().size(), "only the enabled, complete spec compiles");
    }

    @Test
    void completenessRulesPerType() {
        assertTrue(new AssertionSpec(ResponseAssertions.Type.STATUS_EQUALS, "", "200", "").isComplete());
        assertFalse(new AssertionSpec(ResponseAssertions.Type.STATUS_EQUALS, "", "abc", "").isComplete());
        assertTrue(new AssertionSpec(ResponseAssertions.Type.STATUS_IN_RANGE, "", "200", "299").isComplete());
        assertFalse(new AssertionSpec(ResponseAssertions.Type.STATUS_IN_RANGE, "", "200", "").isComplete());
        assertTrue(new AssertionSpec(ResponseAssertions.Type.HEADER_EQUALS, "Content-Type", "x", "").isComplete());
        assertFalse(new AssertionSpec(ResponseAssertions.Type.HEADER_EQUALS, "", "x", "").isComplete());
        assertTrue(new AssertionSpec(ResponseAssertions.Type.BODY_CONTAINS, "", "hi", "").isComplete());
        assertFalse(new AssertionSpec(ResponseAssertions.Type.BODY_CONTAINS, "", "", "").isComplete());
    }

    @Test
    void evaluatesAgainstAResponse() {
        var specs = List.of(
                new AssertionSpec(ResponseAssertions.Type.STATUS_IN_RANGE, "", "200", "299"),
                new AssertionSpec(ResponseAssertions.Type.HEADER_CONTAINS, "Content-Type", "json", ""),
                new AssertionSpec(ResponseAssertions.Type.JSON_PATH_EQUALS, "/name", "neo", ""),
                new AssertionSpec(ResponseAssertions.Type.BODY_CONTAINS, "", "missing", ""));

        ResponseAssertions.Report report =
                AssertionSpec.toAssertions(specs).evaluate(response(200, "{\"name\":\"neo\"}"));

        assertEquals(4, report.results().size());
        assertEquals(3, report.passedCount());
        assertEquals(1, report.failedCount());
        assertFalse(report.allPassed());
    }
}
