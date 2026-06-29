package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link HmacAuthenticator} against the canonical RFC 4231 HMAC-SHA256 known-answer vector
 * (Test Case 2: key {@code "Jefe"}, data {@code "what do ya want for nothing?"}) and exercises the
 * template/placeholder, encoding, and {@code Date}-emission behaviour.
 */
class HmacAuthenticatorTest {

    /** A template with no placeholders signs the literal string verbatim — lets us hit the RFC vector. */
    @Test
    void hexSignatureMatchesRfc4231TestCase2() {
        Map<String, String> headers = HmacAuthenticator.sign(
                HmacAuthenticator.Algorithm.HMAC_SHA256, "Jefe",
                HmacAuthenticator.Encoding.HEX,
                "what do ya want for nothing?", "Authorization", "{signature}", "",
                "GET", "https://example.com/", new byte[0], Instant.EPOCH);

        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                headers.get("Authorization"));
        // No {date} in the string-to-sign ⇒ no Date header.
        assertFalse(headers.containsKey("Date"));
    }

    @Test
    void base64EncodingMatchesTheHexVector() {
        Map<String, String> headers = HmacAuthenticator.sign(
                HmacAuthenticator.Algorithm.HMAC_SHA256, "Jefe",
                HmacAuthenticator.Encoding.BASE64,
                "what do ya want for nothing?", "X-Signature", "{signature}", "",
                "GET", "https://example.com/", new byte[0], Instant.EPOCH);

        byte[] expected = hexToBytes("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertEquals(Base64.getEncoder().encodeToString(expected), headers.get("X-Signature"));
    }

    @Test
    void templatePlaceholdersAndNewlineEscapeResolve() {
        // {method}\n{path} over POST /v1/orders → signs "POST\n/v1/orders".
        Map<String, String> headers = HmacAuthenticator.sign(
                HmacAuthenticator.Algorithm.HMAC_SHA256, "Jefe",
                HmacAuthenticator.Encoding.HEX,
                "{method}\\n{path}", "Authorization", "{signature}", "",
                "post", "https://api.example.com/v1/orders?x=1", new byte[0], Instant.EPOCH);

        // Independently HMAC the expected canonical string and compare.
        String expected = HmacAuthenticator.sign(
                HmacAuthenticator.Algorithm.HMAC_SHA256, "Jefe",
                HmacAuthenticator.Encoding.HEX,
                "POST\n/v1/orders", "Authorization", "{signature}", "",
                "GET", "https://x/", new byte[0], Instant.EPOCH).get("Authorization");
        assertEquals(expected, headers.get("Authorization"));
    }

    @Test
    void headerValueTemplateEmbedsKeyIdAndSignature() {
        Map<String, String> headers = HmacAuthenticator.sign(
                HmacAuthenticator.Algorithm.HMAC_SHA256, "secret",
                HmacAuthenticator.Encoding.HEX,
                "{method}", "Authorization", "HMAC {keyId}:{signature}", "AKID-1",
                "GET", "https://example.com/", new byte[0], Instant.EPOCH);

        String value = headers.get("Authorization");
        assertTrue(value.startsWith("HMAC AKID-1:"), value);
    }

    @Test
    void dateHeaderEmittedWhenStringToSignUsesDate() {
        Map<String, String> headers = HmacAuthenticator.sign(
                HmacAuthenticator.Algorithm.HMAC_SHA256, "secret",
                HmacAuthenticator.Encoding.BASE64,
                "{method}\\n{path}\\n{date}", "Authorization", "{signature}", "",
                "GET", "https://example.com/x", new byte[0], Instant.parse("2026-06-29T12:00:00Z"));

        assertEquals("Mon, 29 Jun 2026 12:00:00 GMT", headers.get("Date"));
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
