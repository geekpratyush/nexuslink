package com.nexuslink.protocol.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class SmiFormatterTest {

    // ------------------------------------------------------------------ TimeTicks

    @Test
    void timeTicksZero() {
        assertEquals("0 days, 0:00:00.00", SmiFormatter.formatTimeTicks(0));
    }

    @Test
    void timeTicksExample() {
        // 12345 hundredths = 123.45 s = 2 min 3.45 s
        assertEquals("0 days, 0:02:03.45", SmiFormatter.formatTimeTicks(12345));
    }

    @Test
    void timeTicksMultiDay() {
        // 1 day = 8_640_000 hundredths. Build 2 days, 3h 4m 5.06s.
        long ticks = 2L * 8_640_000L        // 2 days
                + 3L * 3_600L * 100L         // 3 hours
                + 4L * 60L * 100L            // 4 minutes
                + 5L * 100L                  // 5 seconds
                + 6L;                        // 6 hundredths
        assertEquals("2 days, 3:04:05.06", SmiFormatter.formatTimeTicks(ticks));
    }

    @Test
    void timeTicksExactlyOneDay() {
        assertEquals("1 days, 0:00:00.00", SmiFormatter.formatTimeTicks(8_640_000L));
    }

    @Test
    void timeTicksWithRawAppendsTicks() {
        assertEquals("0 days, 0:02:03.45 (12345)", SmiFormatter.formatTimeTicksWithRaw(12345));
    }

    @Test
    void timeTicksNegativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> SmiFormatter.formatTimeTicks(-1));
    }

    // ------------------------------------------------------------------ Counter / Gauge

    @Test
    void counterUnsignedMax() {
        assertEquals("4294967295", SmiFormatter.formatCounter(0xFFFFFFFFL));
    }

    @Test
    void counterSignedTwinIsUnsigned() {
        // -1L has all low 32 bits set -> same as 0xFFFFFFFF
        assertEquals("4294967295", SmiFormatter.formatCounter(-1L));
    }

    @Test
    void counterAboveSignedIntRange() {
        // 0x80000000 = 2147483648, which overflows a signed int
        assertEquals("2147483648", SmiFormatter.formatCounter(0x80000000L));
    }

    @Test
    void gaugeUnsigned() {
        assertEquals("4294967295", SmiFormatter.formatGauge(-1L));
        assertEquals("0", SmiFormatter.formatGauge(0L));
        assertEquals("65535", SmiFormatter.formatGauge(65535L));
    }

    // ------------------------------------------------------------------ Counter64

    @Test
    void counter64BigInteger() {
        assertEquals("18446744073709551615",
                SmiFormatter.formatCounter64(new BigInteger("18446744073709551615")));
    }

    @Test
    void counter64FromTwoWords() {
        // high=0xFFFFFFFF, low=0xFFFFFFFF -> 2^64 - 1
        assertEquals("18446744073709551615", SmiFormatter.formatCounter64(-1L, -1L));
    }

    @Test
    void counter64FromTwoWordsMixed() {
        // high=1, low=0 -> 2^32 = 4294967296
        assertEquals("4294967296", SmiFormatter.formatCounter64(1L, 0L));
    }

    @Test
    void counter64NullRejected() {
        assertThrows(NullPointerException.class, () -> SmiFormatter.formatCounter64(null));
    }

    @Test
    void counter64NegativeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SmiFormatter.formatCounter64(BigInteger.valueOf(-1)));
    }

    // ------------------------------------------------------------------ IpAddress

    @Test
    void ipAddressDottedQuad() {
        byte[] addr = {(byte) 192, (byte) 168, 0, 1};
        assertEquals("192.168.0.1", SmiFormatter.formatIpAddress(addr));
    }

    @Test
    void ipAddressAllHighBits() {
        byte[] addr = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        assertEquals("255.255.255.255", SmiFormatter.formatIpAddress(addr));
    }

    @Test
    void ipAddressWrongLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SmiFormatter.formatIpAddress(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> SmiFormatter.formatIpAddress(new byte[]{1, 2, 3, 4, 5}));
    }

    @Test
    void ipAddressNullRejected() {
        assertThrows(NullPointerException.class, () -> SmiFormatter.formatIpAddress(null));
    }

    // ------------------------------------------------------------------ OctetString

    @Test
    void octetStringPrintableRendersText() {
        byte[] bytes = "public".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("public", SmiFormatter.formatOctetString(bytes));
    }

    @Test
    void octetStringBinaryRendersHex() {
        // MAC-address-style binary: 00 1B 44 11 3A B7 (control byte built numerically)
        byte[] mac = {(byte) 0x00, (byte) 0x1B, (byte) 0x44, (byte) 0x11, (byte) 0x3A, (byte) 0xB7};
        assertEquals("00 1B 44 11 3A B7", SmiFormatter.formatOctetString(mac));
    }

    @Test
    void octetStringEmptyIsEmpty() {
        assertEquals("", SmiFormatter.formatOctetString(new byte[0]));
    }

    @Test
    void octetStringWithControlByteIsHex() {
        // Contains a NUL (0x00) so it is not printable.
        byte[] bytes = {(byte) 0x41, (byte) 0x00, (byte) 0x42};
        assertEquals("41 00 42", SmiFormatter.formatOctetString(bytes));
    }

    @Test
    void octetStringNullRejected() {
        assertThrows(NullPointerException.class, () -> SmiFormatter.formatOctetString(null));
    }

    // ------------------------------------------------------------------ looksPrintable

    @Test
    void looksPrintableTrueForAscii() {
        assertTrue(SmiFormatter.looksPrintable("Hello, World!".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    void looksPrintableEmptyIsTrue() {
        assertTrue(SmiFormatter.looksPrintable(new byte[0]));
    }

    @Test
    void looksPrintableFalseForControl() {
        assertFalse(SmiFormatter.looksPrintable(new byte[]{(byte) 0x09})); // tab is not in [0x20,0x7E]
        assertFalse(SmiFormatter.looksPrintable(new byte[]{(byte) 0x7F})); // DEL
        assertFalse(SmiFormatter.looksPrintable(new byte[]{(byte) 0x80})); // high bit set
    }

    @Test
    void looksPrintableBoundaryBytes() {
        assertTrue(SmiFormatter.looksPrintable(new byte[]{(byte) 0x20, (byte) 0x7E}));
    }

    // ------------------------------------------------------------------ hex

    @Test
    void hexBasic() {
        assertEquals("AA BB CC", SmiFormatter.hex(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}));
    }

    @Test
    void hexSingleByteZeroPadded() {
        assertEquals("0F", SmiFormatter.hex(new byte[]{(byte) 0x0F}));
    }

    @Test
    void hexEmptyIsEmpty() {
        assertEquals("", SmiFormatter.hex(new byte[0]));
    }

    @Test
    void hexNullRejected() {
        assertThrows(NullPointerException.class, () -> SmiFormatter.hex(null));
    }

    // ------------------------------------------------------------------ Object identifiers

    @Test
    void objectIdDotted() {
        assertEquals("1.3.6.1.2.1.1.1.0",
                SmiFormatter.formatObjectId(new long[]{1, 3, 6, 1, 2, 1, 1, 1, 0}));
    }

    @Test
    void objectIdEmptyRejected() {
        assertThrows(OidFormatException.class, () -> SmiFormatter.formatObjectId(new long[0]));
    }

    @Test
    void oidStringNormalisesLeadingDot() {
        assertEquals("1.3.6.1", SmiFormatter.formatOid(".1.3.6.1"));
    }

    @Test
    void oidStringMalformedRejected() {
        assertThrows(OidFormatException.class, () -> SmiFormatter.formatOid("1..2"));
    }
}
