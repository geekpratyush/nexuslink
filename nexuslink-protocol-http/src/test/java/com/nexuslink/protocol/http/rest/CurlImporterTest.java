package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CurlImporterTest {

    /** Returns the value of the first header whose name matches {@code name}, ignoring case. */
    private static Optional<String> header(RestRequest r, String name) {
        return r.getHeaders().stream()
                .filter(kv -> kv.getKey().equalsIgnoreCase(name))
                .map(RestRequest.KeyValue::getValue)
                .findFirst();
    }

    @Test
    void simpleGet() {
        RestRequest r = CurlImporter.fromCurl("curl https://api.example.com/x");
        assertEquals("GET", r.getMethod());
        assertEquals("https://api.example.com/x", r.getUrl());
        assertEquals(RestRequest.AuthType.NONE, r.getAuthType());
        assertTrue(r.getHeaders().isEmpty());
    }

    @Test
    void postWithHeadersAndBody() {
        RestRequest r = CurlImporter.fromCurl(
                "curl -X POST https://api.example.com/items "
                        + "-H 'Content-Type: application/json' "
                        + "-H 'X-Trace: abc 123' "
                        + "-d '{\"a\":1}'");
        assertEquals("POST", r.getMethod());
        assertEquals("https://api.example.com/items", r.getUrl());
        assertEquals("{\"a\":1}", r.getBody());
        assertEquals(RestRequest.BodyType.JSON, r.getBodyType());
        assertEquals("application/json", header(r, "Content-Type").orElseThrow());
        assertEquals("abc 123", header(r, "X-Trace").orElseThrow());
    }

    @Test
    void implicitPostWhenDataPresent() {
        RestRequest r = CurlImporter.fromCurl("curl https://api.example.com -d name=pratyush");
        assertEquals("POST", r.getMethod());
        assertEquals("name=pratyush", r.getBody());
    }

    @Test
    void basicAuthParsed() {
        RestRequest r = CurlImporter.fromCurl("curl -u alice:s3cret https://api.example.com");
        assertEquals(RestRequest.AuthType.BASIC, r.getAuthType());
        assertEquals("alice", r.getAuthUsername());
        assertEquals("s3cret", r.getAuthPassword());
    }

    @Test
    void basicAuthEmptyPassword() {
        RestRequest r = CurlImporter.fromCurl("curl --user alice: https://api.example.com");
        assertEquals(RestRequest.AuthType.BASIC, r.getAuthType());
        assertEquals("alice", r.getAuthUsername());
        assertEquals("", r.getAuthPassword());
    }

    @Test
    void singleQuotedHeaderWithSpacesStaysIntact() {
        RestRequest r = CurlImporter.fromCurl(
                "curl https://api.example.com -H 'User-Agent: My App/1.0 (test build)'");
        assertEquals("My App/1.0 (test build)", header(r, "User-Agent").orElseThrow());
    }

    @Test
    void multipleDataJoinedWithAmpersand() {
        RestRequest r = CurlImporter.fromCurl(
                "curl https://api.example.com -d a=1 -d b=2 --data c=3");
        assertEquals("a=1&b=2&c=3", r.getBody());
        assertEquals("POST", r.getMethod());
    }

    @Test
    void lineContinuationMultiLineParses() {
        String cmd = "curl -X PUT https://api.example.com/x \\\n"
                + "  -H 'Accept: application/json' \\\n"
                + "  -d 'payload'";
        RestRequest r = CurlImporter.fromCurl(cmd);
        assertEquals("PUT", r.getMethod());
        assertEquals("https://api.example.com/x", r.getUrl());
        assertEquals("application/json", header(r, "Accept").orElseThrow());
        assertEquals("payload", r.getBody());
    }

    @Test
    void bearerHeaderBecomesBearerAuth() {
        RestRequest r = CurlImporter.fromCurl(
                "curl https://api.example.com -H 'Authorization: Bearer tok-123'");
        assertEquals(RestRequest.AuthType.BEARER, r.getAuthType());
        assertEquals("tok-123", r.getAuthToken());
        // The Authorization header is consumed by the auth mapping, not kept as a raw header.
        assertTrue(header(r, "Authorization").isEmpty());
    }

    @Test
    void benignFlagsAreIgnored() {
        RestRequest r = CurlImporter.fromCurl(
                "curl --compressed -s -k -L -i https://api.example.com/x");
        assertEquals("GET", r.getMethod());
        assertEquals("https://api.example.com/x", r.getUrl());
    }

    @Test
    void urlViaFlag() {
        RestRequest r = CurlImporter.fromCurl("curl --url https://api.example.com/y -X DELETE");
        assertEquals("DELETE", r.getMethod());
        assertEquals("https://api.example.com/y", r.getUrl());
    }

    @Test
    void missingUrlThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CurlImporter.fromCurl("curl -X POST -H 'Accept: application/json'"));
        assertTrue(ex.getMessage().toLowerCase().contains("url"));
    }

    @Test
    void tokenizerKeepsQuotedSpacesAsOneToken() {
        List<String> tokens = CurlImporter.tokenize("curl -H 'A: b c' https://x");
        assertEquals(List.of("curl", "-H", "A: b c", "https://x"), tokens);
    }

    @Test
    void dollarQuoteTreatedAsNormalQuote() {
        RestRequest r = CurlImporter.fromCurl(
                "curl https://api.example.com -H $'X-Tag: hello world'");
        assertEquals("hello world", header(r, "X-Tag").orElseThrow());
    }
}
