package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SHA-256 helper behind transfer verification. The expected digests are the published
 * NIST/RFC test vectors, so a regression here means the hash itself changed, not just our formatting.
 */
class ChecksumTest {

    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void hashesKnownVectorFromBytes() {
        assertEquals(ABC_SHA256, Checksum.sha256(utf8("abc")));
    }

    @Test
    void hashesEmptyInput() {
        assertEquals(EMPTY_SHA256, Checksum.sha256(new byte[0]));
    }

    @Test
    void streamAndByteArrayAgreeOnTheSameContent() throws Exception {
        assertEquals(ABC_SHA256, Checksum.sha256(new ByteArrayInputStream(utf8("abc"))));
        assertEquals(EMPTY_SHA256, Checksum.sha256(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void streamingHandlesContentLargerThanTheInternalBuffer() throws Exception {
        // Several buffers' worth, to prove the multi-chunk update path matches a one-shot digest.
        byte[] big = new byte[64 * 1024 * 3 + 17];
        new Random(42).nextBytes(big);
        assertEquals(Checksum.sha256(big), Checksum.sha256(new ByteArrayInputStream(big)));
    }

    @Test
    void differentContentOfTheSameLengthHashesDifferently() {
        // The whole point of hashing on top of the size check.
        assertNotEquals(Checksum.sha256(utf8("hello world")), Checksum.sha256(utf8("hello w0rld")));
    }

    @Test
    void digestIsLowercaseHexOfTheFullDigestWidth() {
        String h = Checksum.sha256(utf8("abc"));
        assertEquals(64, h.length());               // 256 bits as hex
        assertEquals(h.toLowerCase(), h);
        assertTrue(h.matches("[0-9a-f]{64}"), h);
    }

    @Test
    void streamIsLeftOpenForTheCaller() throws Exception {
        // Checksum reads to the end but must not close a stream it does not own.
        var tracker = new ByteArrayInputStream(utf8("abc")) {
            boolean closed;
            @Override public void close() { closed = true; }
        };
        Checksum.sha256(tracker);
        assertFalse(tracker.closed);
    }
}
