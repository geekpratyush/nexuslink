package com.nexuslink.protocol.grpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcStatusCodesTest {

    /** Canonical names in numeric order 0..16. */
    private static final String[] NAMES = {
            "OK", "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED",
            "NOT_FOUND", "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED",
            "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED",
            "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "UNAUTHENTICATED"
    };

    @Test
    void allNumbersMapToRightNameAndBack() {
        for (int i = 0; i < NAMES.length; i++) {
            assertEquals(NAMES[i], GrpcStatusCodes.name(i), "name(" + i + ")");
            assertEquals(i, GrpcStatusCodes.number(NAMES[i]), "number(" + NAMES[i] + ")");
            assertEquals(i, GrpcStatusCodes.byNumber(i).number());
            assertEquals(NAMES[i], GrpcStatusCodes.byNumber(i).statusName());
        }
    }

    @Test
    void listHasExactly17EntriesInCodeOrder() {
        List<GrpcStatusCodes.Code> all = GrpcStatusCodes.all();
        assertEquals(17, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i, all.get(i).number());
            assertEquals(NAMES[i], all.get(i).name());
        }
        assertSame(all, GrpcStatusCodes.ALL);
    }

    @Test
    void byNameIsCaseInsensitiveAndTrimmed() {
        assertEquals(GrpcStatusCodes.Code.UNAVAILABLE, GrpcStatusCodes.byName("UNAVAILABLE").orElseThrow());
        assertEquals(GrpcStatusCodes.Code.UNAVAILABLE, GrpcStatusCodes.byName("unavailable").orElseThrow());
        assertEquals(GrpcStatusCodes.Code.UNAVAILABLE, GrpcStatusCodes.byName("Unavailable").orElseThrow());
        assertEquals(GrpcStatusCodes.Code.NOT_FOUND, GrpcStatusCodes.byName("  not_found  ").orElseThrow());
    }

    @Test
    void byNameUnknownOrNullIsEmpty() {
        assertTrue(GrpcStatusCodes.byName(null).isEmpty());
        assertTrue(GrpcStatusCodes.byName("").isEmpty());
        assertTrue(GrpcStatusCodes.byName("NO_SUCH_CODE").isEmpty());
    }

    @Test
    void numberRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.number("bogus"));
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.number(null));
    }

    @Test
    void byNumberOutOfRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.byNumber(-1));
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.byNumber(17));
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.name(99));
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.description(-5));
        assertThrows(IllegalArgumentException.class, () -> GrpcStatusCodes.httpStatus(17));
    }

    @Test
    void findByNumberIsEmptyOutOfRange() {
        assertTrue(GrpcStatusCodes.findByNumber(-1).isEmpty());
        assertTrue(GrpcStatusCodes.findByNumber(17).isEmpty());
        assertEquals(GrpcStatusCodes.Code.OK, GrpcStatusCodes.findByNumber(0).orElseThrow());
        assertEquals(GrpcStatusCodes.Code.UNAUTHENTICATED, GrpcStatusCodes.findByNumber(16).orElseThrow());
    }

    @Test
    void httpMappingSpotChecks() {
        assertEquals(200, GrpcStatusCodes.httpStatus(0));   // OK
        assertEquals(499, GrpcStatusCodes.httpStatus(1));   // CANCELLED
        assertEquals(400, GrpcStatusCodes.httpStatus(3));   // INVALID_ARGUMENT
        assertEquals(504, GrpcStatusCodes.httpStatus(4));   // DEADLINE_EXCEEDED
        assertEquals(404, GrpcStatusCodes.httpStatus(5));   // NOT_FOUND
        assertEquals(409, GrpcStatusCodes.httpStatus(6));   // ALREADY_EXISTS
        assertEquals(403, GrpcStatusCodes.httpStatus(7));   // PERMISSION_DENIED
        assertEquals(429, GrpcStatusCodes.httpStatus(8));   // RESOURCE_EXHAUSTED
        assertEquals(501, GrpcStatusCodes.httpStatus(12));  // UNIMPLEMENTED
        assertEquals(500, GrpcStatusCodes.httpStatus(13));  // INTERNAL
        assertEquals(503, GrpcStatusCodes.httpStatus(14));  // UNAVAILABLE
        assertEquals(500, GrpcStatusCodes.httpStatus(15));  // DATA_LOSS
        assertEquals(401, GrpcStatusCodes.httpStatus(16));  // UNAUTHENTICATED
        assertEquals(500, GrpcStatusCodes.httpStatus(2));   // UNKNOWN
    }

    @Test
    void descriptionNonEmptyForEveryCode() {
        for (int i = 0; i <= 16; i++) {
            String d = GrpcStatusCodes.description(i);
            assertFalse(d == null || d.isBlank(), "description(" + i + ") must be non-empty");
        }
    }

    @Test
    void listIsImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> GrpcStatusCodes.all().add(GrpcStatusCodes.Code.OK));
    }
}
