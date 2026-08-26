package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResponseExtractionTest {

    private static RestResponse response(String body) {
        return new RestResponse(200, "OK",
                Map.of("Content-Type", List.of("application/json"),
                        "X-Request-Id", List.of("abc-123")),
                body, body == null ? 0 : body.length(), "HTTP/1.1",
                new RestResponse.Timing(1, 1, 1, 1, 1, 5), false, null);
    }

    private static final String LOGIN = """
            {"data": {"token": "eyJhbGci", "user": {"id": 7}}, "expires": 3600}""";

    @Test
    void aJsonPathPullsTheValueOut() {
        ResponseExtraction e = new ResponseExtraction("token",
                ResponseExtraction.Source.JSON_PATH, "/data/token");
        assertEquals("eyJhbGci", e.extract(response(LOGIN)).orElseThrow());
    }

    @Test
    void dottedAndDollarPathsWorkTheSameAsPointers() {
        for (String path : List.of("data.token", "$.data.token", "$data.token", "/data/token")) {
            assertEquals("eyJhbGci", new ResponseExtraction("t",
                    ResponseExtraction.Source.JSON_PATH, path).extract(response(LOGIN)).orElseThrow(), path);
        }
    }

    @Test
    void aNumericOrNestedValueComesBackAsText() {
        assertEquals("7", new ResponseExtraction("id", ResponseExtraction.Source.JSON_PATH,
                "data.user.id").extract(response(LOGIN)).orElseThrow());
        assertTrue(new ResponseExtraction("user", ResponseExtraction.Source.JSON_PATH,
                "data.user").extract(response(LOGIN)).orElseThrow().contains("\"id\":7"));
    }

    @Test
    void aMissingPathIsEmptyRatherThanAnError() {
        assertTrue(new ResponseExtraction("x", ResponseExtraction.Source.JSON_PATH, "data.nope")
                .extract(response(LOGIN)).isEmpty());
        assertTrue(new ResponseExtraction("x", ResponseExtraction.Source.JSON_PATH, "a")
                .extract(response("not json")).isEmpty());
        assertTrue(new ResponseExtraction("x", ResponseExtraction.Source.JSON_PATH, "a")
                .extract(response(null)).isEmpty());
    }

    @Test
    void headersAreMatchedCaseInsensitively() {
        assertEquals("abc-123", new ResponseExtraction("rid", ResponseExtraction.Source.HEADER,
                "x-request-id").extract(response(LOGIN)).orElseThrow());
        assertTrue(new ResponseExtraction("x", ResponseExtraction.Source.HEADER, "X-Absent")
                .extract(response(LOGIN)).isEmpty());
    }

    @Test
    void aRegexReturnsItsFirstGroupOrTheWholeMatch() {
        assertEquals("eyJhbGci", new ResponseExtraction("t", ResponseExtraction.Source.REGEX,
                "\"token\":\\s*\"([^\"]+)\"").extract(response(LOGIN)).orElseThrow());
        assertEquals("3600", new ResponseExtraction("t", ResponseExtraction.Source.REGEX,
                "\\d{4}").extract(response(LOGIN)).orElseThrow());
        assertTrue(new ResponseExtraction("t", ResponseExtraction.Source.REGEX, "nomatch")
                .extract(response(LOGIN)).isEmpty());
    }

    @Test
    void aBrokenRegexIsEmptyRatherThanThrowing() {
        assertTrue(new ResponseExtraction("t", ResponseExtraction.Source.REGEX, "([")
                .extract(response(LOGIN)).isEmpty());
    }

    @Test
    void statusAndWholeBodyNeedNoExpression() {
        assertEquals("200", new ResponseExtraction("code", ResponseExtraction.Source.STATUS, "")
                .extract(response(LOGIN)).orElseThrow());
        assertEquals(LOGIN, new ResponseExtraction("all", ResponseExtraction.Source.BODY, "")
                .extract(response(LOGIN)).orElseThrow());
    }

    @Test
    void anIncompleteExtractionExtractsNothing() {
        assertFalse(new ResponseExtraction("", ResponseExtraction.Source.STATUS, "").isComplete());
        assertFalse(new ResponseExtraction("t", ResponseExtraction.Source.JSON_PATH, "").isComplete());
        assertTrue(new ResponseExtraction("t", ResponseExtraction.Source.STATUS, "").isComplete());
        assertTrue(new ResponseExtraction("", ResponseExtraction.Source.STATUS, "")
                .extract(response(LOGIN)).isEmpty());
    }

    @Test
    void theCompactFormRoundTrips() {
        List<ResponseExtraction> parsed = ResponseExtraction.parse("""
                # chain the login
                token = json_path: /data/token
                rid = header: X-Request-Id
                code = status
                """);
        assertEquals(3, parsed.size());
        assertEquals(ResponseExtraction.Source.HEADER, parsed.get(1).source());
        assertEquals("X-Request-Id", parsed.get(1).expression());
        assertEquals(ResponseExtraction.Source.STATUS, parsed.get(2).source());
        assertEquals(3, ResponseExtraction.parse(ResponseExtraction.render(parsed)).size());
    }

    @Test
    void anUnqualifiedLineIsTakenAsAJsonPath() {
        List<ResponseExtraction> parsed = ResponseExtraction.parse("token = data.token");
        assertEquals(ResponseExtraction.Source.JSON_PATH, parsed.get(0).source());
        assertEquals("data.token", parsed.get(0).expression());
    }

    @Test
    void theDescriptionNamesTheVariableAndItsSource() {
        assertEquals("token ← JSON /data/token", new ResponseExtraction("token",
                ResponseExtraction.Source.JSON_PATH, "/data/token").describe());
        assertEquals("code ← status code", new ResponseExtraction("code",
                ResponseExtraction.Source.STATUS, "").describe());
    }
}
