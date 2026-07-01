package com.nexuslink.protocol.snmp;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Renders SNMP/SMI values as human-readable display strings — the kind of formatting a MIB browser
 * applies when it shows the result of a GET. Everything here is pure, static and side-effect free,
 * and the API is deliberately dependency-free: it takes JDK primitives, {@code byte[]} and
 * {@link BigInteger} rather than any SNMP-library value type, so it can format bytes decoded from any
 * source (or from tests) without pulling in SNMP4J.
 *
 * <p>The SMI base types are unsigned even though Java has no unsigned primitives, so the numeric
 * helpers here interpret their input accordingly:
 * <ul>
 *   <li>{@link #formatCounter(long)} and {@link #formatGauge(long)} treat the argument as an unsigned
 *       32-bit quantity (Counter32 / Gauge32 / Unsigned32), masking to 32 bits so that both
 *       {@code 4294967295L} and its signed twin {@code -1L} render as {@code 4294967295}.</li>
 *   <li>{@link #formatCounter64(BigInteger)} and {@link #formatCounter64(long, long)} render an
 *       unsigned 64-bit Counter64.</li>
 * </ul>
 */
public final class SmiFormatter {

    /** Mask that keeps the low 32 bits — used to reinterpret a signed {@code long} as unsigned 32-bit. */
    private static final long U32 = 0xFFFFFFFFL;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SmiFormatter() {
    }

    // ------------------------------------------------------------------ TimeTicks

    /**
     * Formats a TimeTicks value — hundredths of a second since some epoch — as
     * {@code "D days, H:MM:SS.ss"}, e.g. {@code 12345} renders as {@code "0 days, 0:02:03.45"} and
     * {@code 8640000} (one day) as {@code "1 days, 0:00:00.00"}. Hours are not zero-padded; minutes,
     * seconds and hundredths always have two digits.
     *
     * @param hundredths elapsed time in 1/100 s; must be non-negative
     * @throws IllegalArgumentException if {@code hundredths} is negative
     */
    public static String formatTimeTicks(long hundredths) {
        if (hundredths < 0) {
            throw new IllegalArgumentException("TimeTicks must be non-negative: " + hundredths);
        }
        long hundredthsPart = hundredths % 100;
        long totalSeconds = hundredths / 100;
        long days = totalSeconds / 86_400;
        long secOfDay = totalSeconds % 86_400;
        long hours = secOfDay / 3_600;
        long minutes = (secOfDay % 3_600) / 60;
        long seconds = secOfDay % 60;
        return String.format("%d days, %d:%02d:%02d.%02d", days, hours, minutes, seconds, hundredthsPart);
    }

    /**
     * Like {@link #formatTimeTicks(long)} but also appends the raw tick count in parentheses, e.g.
     * {@code "0 days, 0:02:03.45 (12345)"} — the form typically shown alongside sysUpTime.
     */
    public static String formatTimeTicksWithRaw(long hundredths) {
        return formatTimeTicks(hundredths) + " (" + hundredths + ")";
    }

    // ------------------------------------------------------------------ Integers

    /**
     * Renders a Counter32 (a monotonically increasing unsigned 32-bit wrap-around counter). The
     * argument is treated as unsigned 32-bit: only the low 32 bits are used, so {@code -1L} and
     * {@code 0xFFFFFFFFL} both yield {@code "4294967295"}.
     */
    public static String formatCounter(long value) {
        return Long.toString(value & U32);
    }

    /**
     * Renders a Gauge32 / Unsigned32 value. As with {@link #formatCounter(long)}, the argument is
     * interpreted as unsigned 32-bit.
     */
    public static String formatGauge(long value) {
        return Long.toString(value & U32);
    }

    /**
     * Renders a Counter64 given as a non-negative {@link BigInteger}. The value is expected to fit in
     * 64 unsigned bits; it is rendered in plain decimal.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public static String formatCounter64(BigInteger value) {
        if (value == null) {
            throw new NullPointerException("Counter64 value must not be null");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Counter64 must be non-negative: " + value);
        }
        return value.toString();
    }

    /**
     * Renders a Counter64 supplied as two 32-bit halves — {@code high} the most-significant word and
     * {@code low} the least-significant — each interpreted as unsigned 32-bit. This is convenient when
     * a 64-bit value has been split to avoid Java's signed {@code long}.
     */
    public static String formatCounter64(long high, long low) {
        BigInteger value = BigInteger.valueOf(high & U32).shiftLeft(32).or(BigInteger.valueOf(low & U32));
        return value.toString();
    }

    // ------------------------------------------------------------------ IpAddress

    /**
     * Formats a 4-byte IpAddress as a dotted quad {@code "a.b.c.d"}, each octet read unsigned so that a
     * byte of {@code (byte) 0xC0} becomes {@code 192}.
     *
     * @throws NullPointerException if {@code addr} is {@code null}
     * @throws IllegalArgumentException if {@code addr} is not exactly four bytes long
     */
    public static String formatIpAddress(byte[] addr) {
        if (addr == null) {
            throw new NullPointerException("IpAddress bytes must not be null");
        }
        if (addr.length != 4) {
            throw new IllegalArgumentException("IpAddress must be 4 bytes, got " + addr.length);
        }
        return (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
    }

    // ------------------------------------------------------------------ OctetString

    /**
     * Formats an OctetString: if every byte is printable ASCII it is returned as text, otherwise it is
     * rendered as a space-separated hex dump (see {@link #hex(byte[])}). An empty array yields an empty
     * string. Use {@link #looksPrintable(byte[])}, {@link #hex(byte[])} directly to force one form.
     *
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public static String formatOctetString(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("OctetString bytes must not be null");
        }
        return looksPrintable(bytes) ? new String(bytes, StandardCharsets.US_ASCII) : hex(bytes);
    }

    /**
     * True when every byte is printable 7-bit ASCII (in {@code [0x20, 0x7E]}). An empty array is
     * considered printable (it renders as the empty string). Control bytes, {@code 0x7F} and any byte
     * with the high bit set make this return {@code false}.
     *
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public static boolean looksPrintable(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("bytes must not be null");
        }
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x20 || v > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders bytes as an upper-case, space-separated hex dump, e.g. {@code {0xAA, 0xBB, 0xCC}} becomes
     * {@code "AA BB CC"}. An empty array yields an empty string.
     *
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public static String hex(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("bytes must not be null");
        }
        StringBuilder sb = new StringBuilder(Math.max(0, bytes.length * 3 - 1));
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            int v = bytes[i] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Object identifiers

    /**
     * Formats an OBJECT IDENTIFIER given as its sub-identifiers into dotted form, e.g.
     * {@code {1,3,6,1,2,1,1,1,0}} becomes {@code "1.3.6.1.2.1.1.1.0"}. Reuses {@link Oid} for the
     * arithmetic-safe rendering (each sub-identifier must be in {@code [0, 2^32-1]}).
     *
     * @throws OidFormatException if {@code subIds} is {@code null}/empty or any value is out of range
     */
    public static String formatObjectId(long[] subIds) {
        return Oid.of(subIds).toString();
    }

    /**
     * Normalises a dotted OID string, e.g. {@code ".1.3.6.1"} becomes {@code "1.3.6.1"}. Passes through
     * {@link Oid#parse(String)} so malformed input is rejected the same way.
     *
     * @throws OidFormatException if {@code oid} is not a valid dotted OID
     */
    public static String formatOid(String oid) {
        return Oid.parse(oid).toString();
    }
}
