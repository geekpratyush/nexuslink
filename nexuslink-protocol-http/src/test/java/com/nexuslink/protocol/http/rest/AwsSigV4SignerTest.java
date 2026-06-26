package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link AwsSigV4Signer} against the official {@code aws-sig-v4-test-suite} "get-vanilla"
 * known-answer vector, so the full canonical-request → string-to-sign → signing-key → signature
 * chain is exercised offline.
 */
class AwsSigV4SignerTest {

    // Standard test-suite credentials/scope.
    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "service";
    private static final Instant WHEN = Instant.parse("2015-08-30T12:36:00Z");

    @Test
    void getVanillaMatchesKnownAnswer() {
        Map<String, String> headers = AwsSigV4Signer.sign(
                "GET", "https://example.amazonaws.com/", REGION, SERVICE,
                ACCESS_KEY, SECRET_KEY, null, Map.of(), new byte[0], WHEN);

        assertEquals("20150830T123600Z", headers.get("X-Amz-Date"));
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                        + "SignedHeaders=host;x-amz-date, "
                        + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
                headers.get("Authorization"));
    }

    @Test
    void temporaryCredentialsAddSecurityTokenHeader() {
        Map<String, String> headers = AwsSigV4Signer.sign(
                "GET", "https://example.amazonaws.com/", REGION, SERVICE,
                ACCESS_KEY, SECRET_KEY, "SESSIONTOKEN123", Map.of(), new byte[0], WHEN);

        assertEquals("SESSIONTOKEN123", headers.get("X-Amz-Security-Token"));
        // The token participates in the signature, so SignedHeaders now includes it.
        org.junit.jupiter.api.Assertions.assertTrue(
                headers.get("Authorization").contains("x-amz-security-token"));
    }
}
