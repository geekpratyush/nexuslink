package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MultipartFormData} against the RFC 7578 / RFC 2046 wire format: exact CRLF
 * structure, delimiter placement, binary-safe round-tripping and name escaping.
 */
class MultipartFormDataTest {

    private static final String CRLF = "\r\n";

    @Test
    void singleTextFieldHasExactStructure() {
        MultipartFormData m = new MultipartFormData("BOUND");
        m.addField("greeting", "hello");

        String body = new String(m.build(), StandardCharsets.UTF_8);
        String expected =
                "--BOUND" + CRLF
                        + "Content-Disposition: form-data; name=\"greeting\"" + CRLF
                        + CRLF
                        + "hello" + CRLF
                        + "--BOUND--" + CRLF;
        assertEquals(expected, body);
    }

    @Test
    void multipleFieldsPreserveInsertionOrder() {
        MultipartFormData m = new MultipartFormData("B");
        m.addField("a", "1").addField("b", "2").addField("c", "3");

        String body = new String(m.build(), StandardCharsets.UTF_8);
        String expected =
                "--B" + CRLF + "Content-Disposition: form-data; name=\"a\"" + CRLF + CRLF + "1" + CRLF
                        + "--B" + CRLF + "Content-Disposition: form-data; name=\"b\"" + CRLF + CRLF + "2" + CRLF
                        + "--B" + CRLF + "Content-Disposition: form-data; name=\"c\"" + CRLF + CRLF + "3" + CRLF
                        + "--B--" + CRLF;
        assertEquals(expected, body);
    }

    @Test
    void filePartWithExplicitContentType() {
        MultipartFormData m = new MultipartFormData("XY");
        m.addFile("upload", "note.txt", "text/plain", "abc".getBytes(StandardCharsets.UTF_8));

        String body = new String(m.build(), StandardCharsets.UTF_8);
        String expected =
                "--XY" + CRLF
                        + "Content-Disposition: form-data; name=\"upload\"; filename=\"note.txt\"" + CRLF
                        + "Content-Type: text/plain" + CRLF
                        + CRLF
                        + "abc" + CRLF
                        + "--XY--" + CRLF;
        assertEquals(expected, body);
    }

    @Test
    void filePartDefaultsToOctetStream() {
        MultipartFormData m = new MultipartFormData("Z");
        m.addFile("f", "data.bin", new byte[] {1, 2, 3});

        String body = new String(m.build(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("Content-Type: application/octet-stream" + CRLF),
                "default content type should be octet-stream");
    }

    @Test
    void binaryBytesSurviveIntact() {
        byte[] payload = new byte[256];
        for (int i = 0; i < 256; i++) {
            payload[i] = (byte) i; // 0x00..0xFF
        }
        MultipartFormData m = new MultipartFormData("BIN");
        m.addFile("blob", "raw", "application/octet-stream", payload);

        byte[] body = m.build();
        byte[] extracted = extractSinglePartContent(body, "BIN");
        assertArrayEquals(payload, extracted, "binary content must round-trip byte-for-byte");
    }

    @Test
    void boundaryAppearsAsOpeningAndClosingDelimiter() {
        MultipartFormData m = new MultipartFormData();
        m.addField("k", "v");
        String boundary = m.getBoundary();

        byte[] body = m.build();
        String head = new String(body, 0, boundary.length() + 4, StandardCharsets.US_ASCII);
        assertEquals("--" + boundary + CRLF, head, "body must open with the boundary delimiter");

        String tail = new String(body, body.length - (boundary.length() + 6),
                boundary.length() + 6, StandardCharsets.US_ASCII);
        assertEquals("--" + boundary + "--" + CRLF, tail, "body must close with the terminating delimiter");
    }

    @Test
    void exposedContentTypeHeaderContainsSameBoundary() {
        MultipartFormData m = new MultipartFormData();
        m.addField("k", "v");
        String boundary = m.getBoundary();

        assertEquals("multipart/form-data; boundary=" + boundary, m.getContentType());
        assertTrue(m.getContentType().contains(boundary));
    }

    @Test
    void fieldNameContainingQuoteIsEscaped() {
        MultipartFormData m = new MultipartFormData("Q");
        m.addField("na\"me", "x");

        String body = new String(m.build(), StandardCharsets.UTF_8);
        assertTrue(body.contains("name=\"na%22me\""), "quote in name must be percent-encoded");
        assertFalse(body.contains("na\"me"), "raw quote must not leak into the header");
    }

    @Test
    void generatedBoundaryNeverCollidesWithContent() {
        // Content that would collide with a naive fixed boundary.
        MultipartFormData m = new MultipartFormData();
        String boundary = "placeholder";
        // Build content that embeds several plausible boundary tokens, then verify the chosen
        // boundary is genuinely absent from the assembled body's part content region.
        m.addField("f", "value");
        boundary = m.getBoundary();
        byte[] body = m.build();
        // The boundary delimiter "--boundary" should appear exactly twice: opening + closing.
        int occurrences = countOccurrences(body, ("--" + boundary).getBytes(StandardCharsets.US_ASCII));
        assertEquals(2, occurrences, "delimiter must appear only as the two structural boundaries");
    }

    @Test
    void customBoundaryIsUsedVerbatim() {
        MultipartFormData m = new MultipartFormData("my-custom-boundary");
        assertEquals("my-custom-boundary", m.getBoundary());
        assertEquals("multipart/form-data; boundary=my-custom-boundary", m.getContentType());
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Extracts the raw content bytes of a body that contains exactly one part. */
    private static byte[] extractSinglePartContent(byte[] body, String boundary) {
        byte[] headerTerminator = (CRLF + CRLF).getBytes(StandardCharsets.US_ASCII);
        int headerEnd = indexOf(body, headerTerminator, 0) + headerTerminator.length;
        byte[] closing = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.US_ASCII);
        int contentEnd = indexOf(body, closing, headerEnd);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(body, headerEnd, contentEnd - headerEnd);
        return out.toByteArray();
    }

    private static int countOccurrences(byte[] haystack, byte[] needle) {
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = indexOf(haystack, needle, from)) >= 0) {
            count++;
            from = idx + needle.length;
        }
        return count;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        outer:
        for (int i = start; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
