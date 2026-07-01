package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HarExporterTest {

    // ---- fixtures ----

    private static RestRequest getWithQueryAndHeaders() {
        RestRequest r = new RestRequest();
        r.setMethod("GET");
        r.setUrl("https://api.example.com/v1/search");
        r.getQueryParams().add(new RestRequest.KeyValue("q", "socks"));
        r.getQueryParams().add(new RestRequest.KeyValue("limit", "10"));
        r.getHeaders().add(new RestRequest.KeyValue("Accept", "application/json"));
        return r;
    }

    private static RestRequest postJson() {
        RestRequest r = new RestRequest();
        r.setMethod("POST");
        r.setUrl("https://api.example.com/v1/items");
        r.setBodyType(RestRequest.BodyType.JSON);
        r.setBody("{\"name\":\"widget\"}");
        return r;
    }

    private static RestResponse okResponse() {
        return new RestResponse(200, "OK",
                Map.of("Content-Type", List.of("application/json")),
                "{\"id\":1}", 9, "HTTP/1.1",
                new RestResponse.Timing(1, 2, 3, 4, 5, 15), false, "");
    }

    // ---- tests ----

    @Test
    void getExportContainsRequiredHarFields() {
        String har = HarExporter.toHar(getWithQueryAndHeaders(), okResponse(),
                Instant.parse("2026-07-01T12:00:00Z"));

        assertTrue(har.contains("\"version\": \"1.2\""), "log.version");
        assertTrue(har.contains("\"creator\""), "log.creator");
        assertTrue(har.contains("\"entries\""), "log.entries");
        assertTrue(har.contains("\"startedDateTime\": \"2026-07-01T12:00:00Z\""), "startedDateTime");
        assertTrue(har.contains("\"time\": 15"), "top-level time from totalMs");
        assertTrue(har.contains("\"request\""), "request");
        assertTrue(har.contains("\"response\""), "response");
        assertTrue(har.contains("\"timings\""), "timings");
        assertTrue(har.contains("\"method\": \"GET\""), "method");
        assertTrue(har.contains("\"url\": \"https://api.example.com/v1/search?q=socks&limit=10\""), "effective url");

        // query string params surface in queryString[]
        assertTrue(har.contains("\"name\": \"q\""), "query param q");
        assertTrue(har.contains("\"value\": \"socks\""), "query value socks");
        assertTrue(har.contains("\"name\": \"limit\""), "query param limit");
        // request header
        assertTrue(har.contains("\"name\": \"Accept\""), "Accept header");

        // response
        assertTrue(har.contains("\"status\": 200"), "status");
        assertTrue(har.contains("\"statusText\": \"OK\""), "statusText");
        assertTrue(har.contains("\"mimeType\": \"application/json\""), "content mimeType");

        // timings map phases; send unknown -> -1
        assertTrue(har.contains("\"wait\": 4"), "wait = ttfb");
        assertTrue(har.contains("\"receive\": 5"), "receive = download");
        assertTrue(har.contains("\"send\": -1"), "send unknown");

        assertWellFormedJson(har);
    }

    @Test
    void postExportIncludesPostData() {
        String har = HarExporter.toHar(postJson(), okResponse());

        assertTrue(har.contains("\"postData\""), "postData present for body");
        assertTrue(har.contains("\"mimeType\": \"application/json\""), "body mimeType from JSON body type");
        assertTrue(har.contains("\"text\": \"{\\\"name\\\":\\\"widget\\\"}\""), "escaped body text");
        assertTrue(har.contains("\"method\": \"POST\""), "method POST");
        assertWellFormedJson(har);
    }

    @Test
    void headerValueWithQuotesNewlineAndUnicodeIsEscaped() {
        RestRequest r = getWithQueryAndHeaders();
        r.getHeaders().add(new RestRequest.KeyValue("X-Note", "a\"b\ncd☃"));

        String har = HarExporter.toHar(r, okResponse(), Instant.EPOCH);

        // quote -> \" ; newline -> \n ; control U+0001 ->  ; snowman kept verbatim
        assertTrue(har.contains("\"value\": \"a\\\"b\\nc\\u0001d☃\""), "escaped header value");
        assertWellFormedJson(har);
    }

    @Test
    void unknownTimingYieldsNegativeOne() {
        RestResponse resp = new RestResponse(204, "No Content", Map.of(), "", 0, "HTTP/1.1",
                null, false, "");
        String har = HarExporter.toHar(getWithQueryAndHeaders(), resp, Instant.EPOCH);

        assertTrue(har.contains("\"time\": -1"), "time -1 when no timing");
        assertTrue(har.contains("\"wait\": -1"), "wait -1 when no timing");
        assertWellFormedJson(har);
    }

    @Test
    void multipleEntriesAreBothPresent() {
        String har = HarExporter.toHar(List.of(
                new HarExporter.Entry(getWithQueryAndHeaders(), okResponse(), Instant.EPOCH),
                new HarExporter.Entry(postJson(), okResponse(), Instant.EPOCH)));

        assertTrue(har.contains("\"method\": \"GET\""), "first entry");
        assertTrue(har.contains("\"method\": \"POST\""), "second entry");
        assertEquals(2, countOccurrences(har, "\"request\""), "two request objects");
        assertWellFormedJson(har);
    }

    @Test
    void emptyEntryListStillWellFormed() {
        String har = HarExporter.toHar(List.of());
        assertTrue(har.contains("\"entries\": []"), "empty entries array");
        assertWellFormedJson(har);
    }

    // ---- helpers ----

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** Asserts the string is well-formed JSON using a tiny dependency-free recursive parser. */
    private static void assertWellFormedJson(String json) {
        JsonProbe p = new JsonProbe(json);
        p.skipWs();
        p.value();
        p.skipWs();
        assertTrue(p.atEnd(), "trailing content after top-level JSON value at pos " + p.pos);
    }

    /** Minimal RFC 8259 recognizer — throws (fails the test) on any malformed structure. */
    private static final class JsonProbe {
        final String s;
        int pos;

        JsonProbe(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++; else break;
            }
        }

        void value() {
            skipWs();
            assertFalse(atEnd(), "unexpected end of JSON");
            char c = s.charAt(pos);
            switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true");
                case 'f' -> literal("false");
                case 'n' -> literal("null");
                default -> number();
            }
        }

        void object() {
            expect('{');
            skipWs();
            if (peek() == '}') { pos++; return; }
            while (true) {
                skipWs();
                string();
                skipWs();
                expect(':');
                value();
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                expect('}');
                return;
            }
        }

        void array() {
            expect('[');
            skipWs();
            if (peek() == ']') { pos++; return; }
            while (true) {
                value();
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                expect(']');
                return;
            }
        }

        void string() {
            expect('"');
            while (true) {
                assertFalse(atEnd(), "unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return;
                if (c == '\\') {
                    assertFalse(atEnd(), "dangling escape");
                    char e = s.charAt(pos++);
                    if (e == 'u') {
                        for (int i = 0; i < 4; i++) {
                            assertTrue(isHex(s.charAt(pos++)), "bad \\u escape");
                        }
                    } else {
                        assertTrue("\"\\/bfnrt".indexOf(e) >= 0, "bad escape \\" + e);
                    }
                } else {
                    assertTrue(c >= 0x20, "raw control char in string");
                }
            }
        }

        void number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            assertTrue(pos > start, "invalid number at pos " + start);
        }

        void literal(String lit) {
            assertTrue(s.startsWith(lit, pos), "expected literal " + lit + " at pos " + pos);
            pos += lit.length();
        }

        char peek() { return atEnd() ? '\0' : s.charAt(pos); }

        void expect(char c) {
            assertTrue(!atEnd() && s.charAt(pos) == c,
                    "expected '" + c + "' at pos " + pos + " but found '"
                            + (atEnd() ? "<eof>" : s.charAt(pos)) + "'");
            pos++;
        }

        static boolean isHex(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }
    }
}
