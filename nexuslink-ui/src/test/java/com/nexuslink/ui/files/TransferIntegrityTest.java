package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static com.nexuslink.ui.files.TransferIntegrity.Issue.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferIntegrityTest {

    @Test
    void matchingSizeVerifies() {
        TransferIntegrity.Report r = TransferIntegrity.verify(1024, 1024L);
        assertTrue(r.verified());
        assertTrue(r.issues().isEmpty());
    }

    @Test
    void sizeMismatchFails() {
        TransferIntegrity.Report r = TransferIntegrity.verify(1024, 512L);
        assertFalse(r.verified());
        assertEquals(java.util.List.of(SIZE_MISMATCH), r.issues());
        assertEquals(512, r.actualSize());
    }

    @Test
    void missingDestinationFails() {
        TransferIntegrity.Report r = TransferIntegrity.verify(1024, null);
        assertFalse(r.verified());
        assertTrue(r.issues().contains(DESTINATION_MISSING));
        assertFalse(r.issues().contains(SIZE_MISMATCH), "missing takes precedence over size");
        assertEquals(-1, r.actualSize());
    }

    @Test
    void matchingChecksumVerifies() {
        TransferIntegrity.Report r = TransferIntegrity.verify(10, 10L, "ABC123", "abc123");
        assertTrue(r.verified(), "hashes compare case-insensitively");
    }

    @Test
    void checksumMismatchFails() {
        TransferIntegrity.Report r = TransferIntegrity.verify(10, 10L, "deadbeef", "cafebabe");
        assertFalse(r.verified());
        assertEquals(java.util.List.of(CHECKSUM_MISMATCH), r.issues());
    }

    @Test
    void checksumSkippedWhenEitherSideMissing() {
        assertTrue(TransferIntegrity.verify(10, 10L, "abc", null).verified());
        assertTrue(TransferIntegrity.verify(10, 10L, null, "abc").verified());
        assertTrue(TransferIntegrity.verify(10, 10L, "abc", "   ").verified());
    }

    @Test
    void sizeAndChecksumCanBothFail() {
        TransferIntegrity.Report r = TransferIntegrity.verify(10, 8L, "aaaa", "bbbb");
        assertFalse(r.verified());
        assertTrue(r.issues().contains(SIZE_MISMATCH));
        assertTrue(r.issues().contains(CHECKSUM_MISMATCH));
        assertEquals(2, r.issues().size());
    }

    @Test
    void whitespaceAroundHashesIsIgnored() {
        assertTrue(TransferIntegrity.verify(1, 1L, "  abc123  ", "abc123").verified());
    }
}
