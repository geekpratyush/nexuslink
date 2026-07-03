package com.nexuslink.protocol.s3;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link S3PresignedUrl}. Because the signing time is injected, every assertion is
 * deterministic and runs fully offline.
 */
class S3PresignedUrlTest {

    private static final String AK = "AKIAIOSFODNN7EXAMPLE";
    private static final String SK = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "examplebucket";
    private static final Instant T = Instant.parse("2013-05-24T00:00:00Z");

    /**
     * Known-answer test. The expected signature was computed independently with a separate
     * reference implementation of the SigV4 query-string algorithm (Python's {@code hmac}/
     * {@code hashlib}) for these exact inputs and this host format
     * ({@code examplebucket.s3.us-east-1.amazonaws.com}), so it pins the whole
     * canonical-request / string-to-sign / signing-key chain.
     */
    @Test
    void knownAnswerSignatureMatchesIndependentReference() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 86400, T);
        assertEquals("https://examplebucket.s3.us-east-1.amazonaws.com/test.txt"
                        + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                        + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                        + "&X-Amz-Date=20130524T000000Z"
                        + "&X-Amz-Expires=86400"
                        + "&X-Amz-SignedHeaders=host"
                        + "&X-Amz-Signature=762f4fcbacec730d460b0e337f554e569e4fe98643baefad7af1276fe3084e7f",
                url);
    }

    @Test
    void deterministicAcrossCalls() {
        String a = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "dir/obj.bin", 3600, T);
        String b = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "dir/obj.bin", 3600, T);
        assertEquals(a, b);
    }

    @Test
    void virtualHostedStructure() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        assertTrue(url.startsWith("https://examplebucket.s3.us-east-1.amazonaws.com/"), url);
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), url);
        assertTrue(url.contains("X-Amz-Credential="), url);
        assertTrue(url.contains("X-Amz-Date="), url);
        assertTrue(url.contains("X-Amz-Expires=3600"), url);
        assertTrue(url.contains("X-Amz-SignedHeaders=host"), url);
        assertTrue(url.contains("X-Amz-Signature="), url);
    }

    @Test
    void signatureIsSixtyFourLowercaseHexChars() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        String sig = url.substring(url.indexOf("X-Amz-Signature=") + "X-Amz-Signature=".length());
        assertEquals(64, sig.length(), sig);
        assertTrue(sig.matches("[0-9a-f]{64}"), sig);
    }

    @Test
    void changingSecretKeyChangesSignature() {
        String a = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        String b = S3PresignedUrl.get(AK, SK + "X", REGION, BUCKET, "test.txt", 3600, T);
        assertNotEquals(signature(a), signature(b));
    }

    @Test
    void changingRegionChangesSignature() {
        String a = S3PresignedUrl.get(AK, SK, "us-east-1", BUCKET, "test.txt", 3600, T);
        String b = S3PresignedUrl.get(AK, SK, "eu-west-1", BUCKET, "test.txt", 3600, T);
        assertNotEquals(signature(a), signature(b));
    }

    @Test
    void changingObjectKeyChangesSignature() {
        String a = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "a.txt", 3600, T);
        String b = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "b.txt", 3600, T);
        assertNotEquals(signature(a), signature(b));
    }

    @Test
    void changingMethodChangesSignature() {
        String a = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        String b = S3PresignedUrl.put(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        assertNotEquals(signature(a), signature(b));
    }

    @Test
    void changingExpiryChangesSignatureAndValue() {
        String a = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        String b = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 7200, T);
        assertNotEquals(signature(a), signature(b));
        assertTrue(a.contains("X-Amz-Expires=3600"), a);
        assertTrue(b.contains("X-Amz-Expires=7200"), b);
    }

    @Test
    void changingTimeChangesSignature() {
        String a = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        String b = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600,
                Instant.parse("2013-05-25T00:00:00Z"));
        assertNotEquals(signature(a), signature(b));
    }

    @Test
    void sessionTokenAppearsEncodedAndSignedWhenPresent() {
        String token = "FQoGZ+session/token=="; // contains chars that must be encoded
        String url = S3PresignedUrl.presign(S3PresignedUrl.Request.builder()
                .accessKey(AK).secretKey(SK).region(REGION).bucket(BUCKET)
                .objectKey("test.txt").method("GET").expirySeconds(3600)
                .sessionToken(token).build(), T);
        assertTrue(url.contains("X-Amz-Security-Token="), url);
        // '+', '/' and '=' must be percent-encoded in the value.
        assertTrue(url.contains("X-Amz-Security-Token=FQoGZ%2Bsession%2Ftoken%3D%3D"), url);

        // The token participates in signing: same inputs without it yield a different signature.
        String without = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        assertNotEquals(signature(without), signature(url));
    }

    @Test
    void sessionTokenAbsentWhenNotSupplied() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        assertFalse(url.contains("X-Amz-Security-Token"), url);
    }

    @Test
    void pathStyleHostAndUri() {
        String url = S3PresignedUrl.presign(S3PresignedUrl.Request.builder()
                .accessKey(AK).secretKey(SK).region(REGION).bucket(BUCKET)
                .objectKey("test.txt").method("GET").expirySeconds(3600)
                .pathStyle(true).build(), T);
        assertTrue(url.startsWith("https://s3.us-east-1.amazonaws.com/examplebucket/test.txt?"), url);
    }

    @Test
    void virtualHostedDoesNotEmbedBucketInPath() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        assertFalse(url.startsWith("https://s3."), url);
        assertTrue(url.startsWith("https://examplebucket.s3."), url);
    }

    @Test
    void objectKeyWithSpacesAndSubdirIsPercentEncoded() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "my folder/file (1).txt", 3600, T);
        // Path segments encoded, '/' preserved as separator.
        assertTrue(url.contains("/my%20folder/file%20%281%29.txt?"), url);
        // No raw space or parenthesis leaked into the path.
        assertFalse(url.contains("my folder"), url);
        assertFalse(url.contains("(1)"), url);
    }

    @Test
    void tildeInKeyIsNotEncoded() {
        String url = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "a~b.txt", 3600, T);
        assertTrue(url.contains("/a~b.txt?"), url);
    }

    @Test
    void putHelperUsesPutMethodSemantics() {
        String get = S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        String put = S3PresignedUrl.put(AK, SK, REGION, BUCKET, "test.txt", 3600, T);
        // Only the signature differs; the query structure is otherwise identical.
        assertNotEquals(signature(get), signature(put));
        assertTrue(put.contains("X-Amz-SignedHeaders=host"), put);
    }

    @Test
    void blankAccessKeyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> S3PresignedUrl.get("  ", SK, REGION, BUCKET, "test.txt", 3600, T));
    }

    @Test
    void blankSecretKeyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> S3PresignedUrl.get(AK, "", REGION, BUCKET, "test.txt", 3600, T));
    }

    @Test
    void badMethodRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> S3PresignedUrl.presign(S3PresignedUrl.Request.builder()
                        .accessKey(AK).secretKey(SK).region(REGION).bucket(BUCKET)
                        .objectKey("test.txt").method("DELETE").expirySeconds(3600).build(), T));
    }

    @Test
    void expiryOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 0, T));
        assertThrows(IllegalArgumentException.class,
                () -> S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 604801, T));
    }

    @Test
    void maxExpiryAccepted() {
        assertDoesNotThrow(
                () -> S3PresignedUrl.get(AK, SK, REGION, BUCKET, "test.txt", 604800, T));
    }

    /** Extracts the hex signature value from a presigned URL. */
    private static String signature(String url) {
        return url.substring(url.indexOf("X-Amz-Signature=") + "X-Amz-Signature=".length());
    }
}
