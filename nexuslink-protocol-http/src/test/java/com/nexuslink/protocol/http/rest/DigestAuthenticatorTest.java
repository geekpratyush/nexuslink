package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DigestAuthenticator} against the canonical RFC 2617 §3.5 known-answer vector
 * (Mufasa / Circle Of Life) and exercises challenge parsing.
 */
class DigestAuthenticatorTest {

    @Test
    void computeResponseMatchesRfc2617Vector() {
        String response = DigestAuthenticator.computeResponse(
                "Mufasa", "testrealm@host.com", "Circle Of Life",
                "GET", "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093",
                "00000001", "0a4f113b", "auth");
        assertEquals("6629fae49393a05397450978507c4ef1", response);
    }

    @Test
    void parseChallengeReadsAllParameters() {
        Map<String, String> c = DigestAuthenticator.parseChallenge(
                "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", "
                        + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
                        + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
        assertEquals("testrealm@host.com", c.get("realm"));
        assertEquals("auth,auth-int", c.get("qop"));
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", c.get("nonce"));
        assertEquals("5ccc069c403ebaf9f0171e9517f40e41", c.get("opaque"));
    }

    @Test
    void authorizationHeaderEmbedsTheKnownAnswerResponse() {
        Map<String, String> challenge = DigestAuthenticator.parseChallenge(
                "Digest realm=\"testrealm@host.com\", qop=\"auth\", "
                        + "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", "
                        + "opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"");
        String header = DigestAuthenticator.authorization(
                challenge, "Mufasa", "Circle Of Life", "GET", "/dir/index.html",
                "0a4f113b", "00000001");

        assertTrue(header.startsWith("Digest "));
        assertTrue(header.contains("username=\"Mufasa\""));
        assertTrue(header.contains("qop=auth"));
        assertTrue(header.contains("nc=00000001"));
        assertTrue(header.contains("cnonce=\"0a4f113b\""));
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""));
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
    }

    @Test
    void legacyNoQopFallsBackToRfc2069Form() {
        // No qop in the challenge → response = MD5(HA1:nonce:HA2).
        String response = DigestAuthenticator.computeResponse(
                "Mufasa", "testrealm@host.com", "Circle Of Life",
                "GET", "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093", null, null, null);
        assertEquals(32, response.length());   // a valid MD5 hex digest
    }
}
