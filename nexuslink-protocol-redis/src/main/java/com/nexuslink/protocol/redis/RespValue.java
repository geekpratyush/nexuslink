package com.nexuslink.protocol.redis;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A decoded RESP (Redis Serialization Protocol) value. Covers the RESP2 wire types plus the common
 * RESP3 additions. Text payloads are UTF-8; {@link BulkString} keeps raw bytes so it is binary-safe.
 *
 * <p>This is a closed, typed hierarchy — pattern-match over it to consume replies:
 * <pre>{@code
 * RespValue reply = new RespCodec().decode(bytes);
 * String s = switch (reply) {
 *     case RespValue.SimpleString ss -> ss.value();
 *     case RespValue.BulkString bs   -> bs.asText();
 *     default                        -> reply.toString();
 * };
 * }</pre>
 */
public sealed interface RespValue
        permits RespValue.SimpleString, RespValue.RespError, RespValue.RespInteger,
                RespValue.BulkString, RespValue.RespArray, RespValue.RespNull,
                RespValue.RespBoolean, RespValue.RespDouble, RespValue.BigNumber,
                RespValue.BulkError, RespValue.VerbatimString, RespValue.RespMap,
                RespValue.RespSet, RespValue.RespPush {

    /** RESP2 {@code +OK\r\n}. */
    record SimpleString(String value) implements RespValue {}

    /** RESP2 {@code -ERR message\r\n}. */
    record RespError(String message) implements RespValue {}

    /** RESP2 {@code :123\r\n}. */
    record RespInteger(long value) implements RespValue {}

    /**
     * RESP2 {@code $5\r\nhello\r\n}, or the null bulk {@code $-1\r\n} when {@link #bytes()} is
     * {@code null}. Stored as raw bytes so arbitrary binary payloads survive a round trip; equality
     * compares the byte contents (unlike the default record behaviour for arrays).
     */
    record BulkString(byte[] bytes) implements RespValue {

        /** A null bulk string ({@code $-1\r\n}). */
        public static final BulkString NULL = new BulkString(null);

        /** Wraps a UTF-8 encoding of {@code text} (or the null bulk when {@code text} is null). */
        public static BulkString of(String text) {
            return text == null ? NULL : new BulkString(text.getBytes(StandardCharsets.UTF_8));
        }

        public boolean isNull() { return bytes == null; }

        /** Decodes the payload as UTF-8, or {@code null} for the null bulk. */
        public String asText() {
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }

        @Override public boolean equals(Object o) {
            return o instanceof BulkString other && Arrays.equals(bytes, other.bytes);
        }

        @Override public int hashCode() { return Arrays.hashCode(bytes); }

        @Override public String toString() {
            return "BulkString[" + (bytes == null ? "null" : asText()) + "]";
        }
    }

    /** RESP2 {@code *2\r\n...}, or the null array {@code *-1\r\n} when {@link #items()} is null. */
    record RespArray(List<RespValue> items) implements RespValue {
        public boolean isNull() { return items == null; }
    }

    /** RESP3 null {@code _\r\n}. */
    record RespNull() implements RespValue {
        public static final RespNull INSTANCE = new RespNull();
    }

    /** RESP3 boolean {@code #t\r\n} / {@code #f\r\n}. */
    record RespBoolean(boolean value) implements RespValue {}

    /** RESP3 double {@code ,3.14\r\n} (also {@code ,inf}, {@code ,-inf}, {@code ,nan}). */
    record RespDouble(double value) implements RespValue {}

    /** RESP3 big number {@code (3492890328409238509324850943850943825024385\r\n}. */
    record BigNumber(BigInteger value) implements RespValue {}

    /** RESP3 bulk error {@code !21\r\nSYNTAX invalid syntax\r\n}. */
    record BulkError(String message) implements RespValue {}

    /** RESP3 verbatim string {@code =15\r\ntxt:Some string\r\n}: a 3-char format tag plus text. */
    record VerbatimString(String format, String value) implements RespValue {}

    /** RESP3 map {@code %2\r\n...}. Insertion order is preserved by the codec. */
    record RespMap(Map<RespValue, RespValue> entries) implements RespValue {}

    /** RESP3 set {@code ~2\r\n...}. Kept as a list because RESP does not forbid duplicates. */
    record RespSet(List<RespValue> items) implements RespValue {}

    /** RESP3 push {@code >2\r\n...} (out-of-band pub/sub style messages). */
    record RespPush(List<RespValue> items) implements RespValue {}
}
