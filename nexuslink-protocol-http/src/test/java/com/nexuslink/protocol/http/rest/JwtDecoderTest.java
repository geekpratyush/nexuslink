package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtDecoderTest {

    /** The canonical jwt.io example token, HS256 / {"sub":"1234567890","name":"John Doe","iat":1516239022}. */
    private static final String CLASSIC =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
            + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"
            + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

    // ---- helpers ----

    private static String enc(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(String headerJson, String payloadJson, String signature) {
        return enc(headerJson) + "." + enc(payloadJson) + "." + signature;
    }

    // ---- the classic token ----

    @Test
    void decodesClassicTokenHeaderAndClaims() {
        DecodedJwt jwt = JwtDecoder.decode(CLASSIC);

        assertEquals("HS256", jwt.algorithm());
        assertEquals("JWT", jwt.type());
        assertNull(jwt.keyId());

        assertEquals("1234567890", jwt.subject());
        assertEquals("John Doe", jwt.claim("name"));
        assertEquals(Instant.ofEpochSecond(1516239022), jwt.issuedAt());
    }

    @Test
    void exposesRawHeaderAndPayloadJson() {
        DecodedJwt jwt = JwtDecoder.decode(CLASSIC);
        assertEquals("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", jwt.headerJson());
        assertEquals("{\"sub\":\"1234567890\",\"name\":\"John Doe\",\"iat\":1516239022}",
                jwt.payloadJson());
    }

    @Test
    void keepsSignatureVerbatimAndDecodesItsBytesWithoutVerifying() {
        DecodedJwt jwt = JwtDecoder.decode(CLASSIC);
        assertEquals("SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c", jwt.signature());
        byte[] sig = jwt.signatureBytes();
        assertEquals(32, sig.length); // HS256 => 32-byte HMAC, decoded but never checked
    }

    // ---- signature is never verified ----

    @Test
    void decodesEvenWhenSignatureIsGarbage() {
        // A wrong/forged signature must still decode: this decoder inspects, it does not authenticate.
        String forged = token("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"alice\"}", "not-a-real-signature__");
        DecodedJwt jwt = JwtDecoder.decode(forged);
        assertEquals("alice", jwt.subject());
        assertEquals("not-a-real-signature__", jwt.signature());
    }

    @Test
    void decodesUnsecuredJwsWithEmptySignature() {
        String unsecured = token("{\"alg\":\"none\"}", "{\"sub\":\"bob\"}", "");
        DecodedJwt jwt = JwtDecoder.decode(unsecured);
        assertEquals("none", jwt.algorithm());
        assertEquals("", jwt.signature());
        assertEquals(0, jwt.signatureBytes().length);
        assertEquals("bob", jwt.subject());
    }

    // ---- time claims ----

    @Test
    void expiredTokenReportsExpired() {
        Instant past = Instant.parse("2000-01-01T00:00:00Z");
        String t = token("{\"alg\":\"HS256\"}",
                "{\"exp\":" + past.getEpochSecond() + "}", "sig");
        DecodedJwt jwt = JwtDecoder.decode(t);

        assertEquals(past, jwt.expiration());
        assertTrue(jwt.isExpired(Instant.now()));
        assertFalse(jwt.isExpired(past.minusSeconds(1)));
    }

    @Test
    void notYetValidTokenReportsNotYetValid() {
        Instant future = Instant.now().plusSeconds(3600);
        String t = token("{\"alg\":\"HS256\"}",
                "{\"nbf\":" + future.getEpochSecond() + "}", "sig");
        DecodedJwt jwt = JwtDecoder.decode(t);

        assertEquals(Instant.ofEpochSecond(future.getEpochSecond()), jwt.notBefore());
        assertTrue(jwt.isNotYetValid(Instant.now()));
        assertFalse(jwt.isNotYetValid(future.plusSeconds(1)));
    }

    @Test
    void expiredIsInclusiveAtTheExactInstant() {
        Instant exp = Instant.ofEpochSecond(1_700_000_000L);
        String t = token("{\"alg\":\"HS256\"}", "{\"exp\":1700000000}", "sig");
        DecodedJwt jwt = JwtDecoder.decode(t);
        assertTrue(jwt.isExpired(exp));            // now == exp counts as expired
        assertFalse(jwt.isExpired(exp.minusNanos(1)));
    }

    @Test
    void missingTimeClaimsAreNeitherExpiredNorNotYetValid() {
        DecodedJwt jwt = JwtDecoder.decode(CLASSIC); // has iat only
        assertNull(jwt.expiration());
        assertNull(jwt.notBefore());
        assertFalse(jwt.isExpired(Instant.now()));
        assertFalse(jwt.isNotYetValid(Instant.now()));
    }

    // ---- registered claims coverage ----

    @Test
    void exposesAllRegisteredClaims() {
        String payload = "{\"iss\":\"issuer.example\",\"sub\":\"subj\",\"aud\":\"the-aud\","
                + "\"exp\":1700000100,\"iat\":1700000000,\"nbf\":1700000050,\"jti\":\"abc-123\"}";
        DecodedJwt jwt = JwtDecoder.decode(token("{\"alg\":\"RS256\",\"kid\":\"key-1\"}", payload, "sig"));

        assertEquals("RS256", jwt.algorithm());
        assertEquals("key-1", jwt.keyId());
        assertEquals("issuer.example", jwt.issuer());
        assertEquals("subj", jwt.subject());
        assertEquals("the-aud", jwt.audience());
        assertEquals(Instant.ofEpochSecond(1700000100), jwt.expiration());
        assertEquals(Instant.ofEpochSecond(1700000000), jwt.issuedAt());
        assertEquals(Instant.ofEpochSecond(1700000050), jwt.notBefore());
        assertEquals("abc-123", jwt.jwtId());
    }

    @Test
    void audienceArrayIsJoined() {
        String payload = "{\"aud\":[\"one\",\"two\",\"three\"]}";
        DecodedJwt jwt = JwtDecoder.decode(token("{\"alg\":\"HS256\"}", payload, "sig"));
        assertEquals("one, two, three", jwt.audience());
    }

    @Test
    void claimAccessorRendersScalarsAndSkipsStructures() {
        String payload = "{\"role\":\"admin\",\"count\":7,\"active\":true,\"obj\":{\"x\":1},\"arr\":[1,2]}";
        DecodedJwt jwt = JwtDecoder.decode(token("{\"alg\":\"HS256\"}", payload, "sig"));
        assertEquals("admin", jwt.claim("role"));
        assertEquals("7", jwt.claim("count"));
        assertEquals("true", jwt.claim("active"));
        assertNull(jwt.claim("obj"));   // structured claims are not stringified
        assertNull(jwt.claim("arr"));
        assertNull(jwt.claim("absent"));
    }

    // ---- base64url specifics ----

    @Test
    void decodesBase64UrlWithoutPadding() {
        // "{}" -> "e30" (no padding); a real header needing padding is exercised via the classic token.
        String t = "e30.e30.sig";
        DecodedJwt jwt = JwtDecoder.decode(t);
        assertEquals("{}", jwt.headerJson());
        assertEquals("{}", jwt.payloadJson());
        assertNull(jwt.algorithm());
    }

    @Test
    void decodesUrlSafeAlphabet() {
        // Payload chosen so its Base64URL encoding contains '-' or '_' rather than '+' or '/'.
        String payload = "{\"data\":\"<<<???>>>\"}";
        DecodedJwt jwt = JwtDecoder.decode(token("{\"alg\":\"HS256\"}", payload, "sig"));
        assertEquals("<<<???>>>", jwt.claim("data"));
    }

    // ---- malformed input ----

    @Test
    void rejectsNullToken() {
        assertThrows(JwtDecodeException.class, () -> JwtDecoder.decode(null));
    }

    @Test
    void rejectsTwoSegments() {
        assertThrows(JwtDecodeException.class,
                () -> JwtDecoder.decode(enc("{\"alg\":\"HS256\"}") + "." + enc("{}")));
    }

    @Test
    void rejectsFourSegments() {
        assertThrows(JwtDecodeException.class,
                () -> JwtDecoder.decode("a.b.c.d"));
    }

    @Test
    void rejectsBadBase64UrlHeader() {
        String t = "!!!not-base64!!!." + enc("{}") + ".sig";
        assertThrows(JwtDecodeException.class, () -> JwtDecoder.decode(t));
    }

    @Test
    void rejectsNonJsonPayload() {
        String t = enc("{\"alg\":\"HS256\"}") + "." + enc("this is not json") + ".sig";
        assertThrows(JwtDecodeException.class, () -> JwtDecoder.decode(t));
    }

    @Test
    void rejectsNonObjectHeader() {
        // valid Base64URL + valid JSON, but a JSON array rather than an object
        String t = enc("[1,2,3]") + "." + enc("{}") + ".sig";
        assertThrows(JwtDecodeException.class, () -> JwtDecoder.decode(t));
    }

    @Test
    void rejectsTruncatedJsonPayload() {
        String t = enc("{\"alg\":\"HS256\"}") + "." + enc("{\"sub\":") + ".sig";
        assertThrows(JwtDecodeException.class, () -> JwtDecoder.decode(t));
    }

    @Test
    void signatureBytesOfEmptySignatureIsEmpty() {
        DecodedJwt jwt = JwtDecoder.decode(token("{\"alg\":\"none\"}", "{}", ""));
        assertArrayEquals(new byte[0], jwt.signatureBytes());
    }
}
