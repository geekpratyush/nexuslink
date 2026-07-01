package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespCodecTest {

    private final RespCodec codec = new RespCodec();

    private static byte[] bytes(String wire) {
        return wire.getBytes(StandardCharsets.UTF_8);
    }

    private RespValue decode(String wire) {
        return codec.decode(bytes(wire));
    }

    // ------------------------------------------------------------------ encoding

    @Nested
    class EncodingCommands {

        @Test
        void encodesSetCommandToExactBytes() {
            byte[] out = codec.encodeCommand("SET", "key", "value");
            assertEquals("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                    new String(out, StandardCharsets.UTF_8));
        }

        @Test
        void encodesSingleWordCommand() {
            byte[] out = codec.encodeCommand("PING");
            assertEquals("*1\r\n$4\r\nPING\r\n", new String(out, StandardCharsets.UTF_8));
        }

        @Test
        void encodesBinarySafeArguments() {
            byte[] payload = {0x00, (byte) 0xFF, '\r', '\n', 0x10};
            byte[] out = codec.encodeCommand(List.of("SET".getBytes(StandardCharsets.UTF_8),
                    "k".getBytes(StandardCharsets.UTF_8), payload));
            // The 5-byte payload (including an embedded CRLF) must survive verbatim.
            byte[] expected = concat(
                    bytes("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\n"),
                    payload,
                    bytes("\r\n"));
            assertArrayEquals(expected, out);
        }

        @Test
        void emptyCommandRejected() {
            assertThrows(RespException.class, codec::encodeCommand);
            assertThrows(RespException.class, () -> codec.encodeCommand(List.of()));
        }

        @Test
        void nullArgumentRejected() {
            assertThrows(RespException.class, () -> codec.encodeCommand("GET", null));
        }
    }

    // ------------------------------------------------------------------ RESP2 decoding

    @Nested
    class DecodingResp2 {

        @Test
        void simpleString() {
            RespValue v = decode("+OK\r\n");
            assertEquals(new RespValue.SimpleString("OK"), v);
        }

        @Test
        void error() {
            RespValue v = decode("-ERR unknown command 'FOO'\r\n");
            assertEquals(new RespValue.RespError("ERR unknown command 'FOO'"), v);
        }

        @Test
        void integer() {
            assertEquals(new RespValue.RespInteger(1000), decode(":1000\r\n"));
            assertEquals(new RespValue.RespInteger(-42), decode(":-42\r\n"));
        }

        @Test
        void bulkString() {
            RespValue v = decode("$5\r\nhello\r\n");
            RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, v);
            assertEquals("hello", bulk.asText());
            assertFalse(bulk.isNull());
        }

        @Test
        void emptyBulkString() {
            RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, decode("$0\r\n\r\n"));
            assertEquals("", bulk.asText());
        }

        @Test
        void nullBulkString() {
            RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, decode("$-1\r\n"));
            assertTrue(bulk.isNull());
            assertEquals(RespValue.BulkString.NULL, bulk);
        }

        @Test
        void binarySafeBulkStringPreservesRawBytes() {
            byte[] payload = {0x00, (byte) 0xFF, '\r', '\n', 0x7F};
            byte[] wire = concat(bytes("$5\r\n"), payload, bytes("\r\n"));
            RespValue.BulkString bulk = assertInstanceOf(RespValue.BulkString.class, codec.decode(wire));
            assertArrayEquals(payload, bulk.bytes());
        }

        @Test
        void array() {
            RespValue v = decode("*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
            RespValue.RespArray arr = assertInstanceOf(RespValue.RespArray.class, v);
            assertEquals(List.of(RespValue.BulkString.of("foo"), RespValue.BulkString.of("bar")),
                    arr.items());
        }

        @Test
        void arrayOfIntegers() {
            RespValue v = decode("*3\r\n:1\r\n:2\r\n:3\r\n");
            RespValue.RespArray arr = assertInstanceOf(RespValue.RespArray.class, v);
            assertEquals(List.of(new RespValue.RespInteger(1), new RespValue.RespInteger(2),
                    new RespValue.RespInteger(3)), arr.items());
        }

        @Test
        void emptyArray() {
            RespValue.RespArray arr = assertInstanceOf(RespValue.RespArray.class, decode("*0\r\n"));
            assertTrue(arr.items().isEmpty());
        }

        @Test
        void nullArray() {
            RespValue.RespArray arr = assertInstanceOf(RespValue.RespArray.class, decode("*-1\r\n"));
            assertTrue(arr.isNull());
        }

        @Test
        void nestedArrays() {
            // [[1, 2, 3], ["Foo", -ERR Bar]]
            String wire = "*2\r\n*3\r\n:1\r\n:2\r\n:3\r\n*2\r\n+Foo\r\n-Bar\r\n";
            RespValue.RespArray outer = assertInstanceOf(RespValue.RespArray.class, decode(wire));
            assertEquals(2, outer.items().size());

            RespValue.RespArray first = assertInstanceOf(RespValue.RespArray.class, outer.items().get(0));
            assertEquals(List.of(new RespValue.RespInteger(1), new RespValue.RespInteger(2),
                    new RespValue.RespInteger(3)), first.items());

            RespValue.RespArray second = assertInstanceOf(RespValue.RespArray.class, outer.items().get(1));
            assertEquals(List.of(new RespValue.SimpleString("Foo"), new RespValue.RespError("Bar")),
                    second.items());
        }

        @Test
        void arrayContainingNullBulk() {
            RespValue.RespArray arr = assertInstanceOf(RespValue.RespArray.class,
                    decode("*3\r\n$3\r\nfoo\r\n$-1\r\n$3\r\nbar\r\n"));
            assertEquals(RespValue.BulkString.NULL, arr.items().get(1));
        }
    }

    // ------------------------------------------------------------------ RESP3 decoding

    @Nested
    class DecodingResp3 {

        @Test
        void nullType() {
            assertEquals(RespValue.RespNull.INSTANCE, decode("_\r\n"));
        }

        @Test
        void booleans() {
            assertEquals(new RespValue.RespBoolean(true), decode("#t\r\n"));
            assertEquals(new RespValue.RespBoolean(false), decode("#f\r\n"));
        }

        @Test
        void doubles() {
            assertEquals(new RespValue.RespDouble(3.14), decode(",3.14\r\n"));
            assertEquals(new RespValue.RespDouble(10.0), decode(",10\r\n"));
            assertEquals(new RespValue.RespDouble(Double.POSITIVE_INFINITY), decode(",inf\r\n"));
            assertEquals(new RespValue.RespDouble(Double.NEGATIVE_INFINITY), decode(",-inf\r\n"));
            assertTrue(Double.isNaN(
                    assertInstanceOf(RespValue.RespDouble.class, decode(",nan\r\n")).value()));
        }

        @Test
        void bigNumber() {
            String huge = "3492890328409238509324850943850943825024385";
            assertEquals(new RespValue.BigNumber(new BigInteger(huge)), decode("(" + huge + "\r\n"));
        }

        @Test
        void bulkError() {
            assertEquals(new RespValue.BulkError("SYNTAX invalid syntax"),
                    decode("!21\r\nSYNTAX invalid syntax\r\n"));
        }

        @Test
        void verbatimString() {
            RespValue.VerbatimString v = assertInstanceOf(RespValue.VerbatimString.class,
                    decode("=15\r\ntxt:Some string\r\n"));
            assertEquals("txt", v.format());
            assertEquals("Some string", v.value());
        }

        @Test
        void map() {
            RespValue.RespMap m = assertInstanceOf(RespValue.RespMap.class,
                    decode("%2\r\n+first\r\n:1\r\n+second\r\n:2\r\n"));
            Map<RespValue, RespValue> entries = m.entries();
            assertEquals(2, entries.size());
            assertEquals(new RespValue.RespInteger(1), entries.get(new RespValue.SimpleString("first")));
            assertEquals(new RespValue.RespInteger(2), entries.get(new RespValue.SimpleString("second")));
        }

        @Test
        void set() {
            RespValue.RespSet s = assertInstanceOf(RespValue.RespSet.class,
                    decode("~3\r\n+a\r\n+b\r\n+c\r\n"));
            assertEquals(List.of(new RespValue.SimpleString("a"), new RespValue.SimpleString("b"),
                    new RespValue.SimpleString("c")), s.items());
        }

        @Test
        void push() {
            RespValue.RespPush p = assertInstanceOf(RespValue.RespPush.class,
                    decode(">2\r\n$7\r\nmessage\r\n$5\r\nhello\r\n"));
            assertEquals(List.of(RespValue.BulkString.of("message"), RespValue.BulkString.of("hello")),
                    p.items());
        }
    }

    // ------------------------------------------------------------------ round trips

    @Nested
    class RoundTrips {

        @Test
        void resp2Types() {
            assertRoundTrip(new RespValue.SimpleString("OK"));
            assertRoundTrip(new RespValue.RespError("ERR boom"));
            assertRoundTrip(new RespValue.RespInteger(-99));
            assertRoundTrip(RespValue.BulkString.of("hello"));
            assertRoundTrip(RespValue.BulkString.NULL);
            assertRoundTrip(new RespValue.RespArray(List.of(
                    RespValue.BulkString.of("a"), new RespValue.RespInteger(2))));
            assertRoundTrip(new RespValue.RespArray(null));
        }

        @Test
        void resp3Types() {
            assertRoundTrip(RespValue.RespNull.INSTANCE);
            assertRoundTrip(new RespValue.RespBoolean(true));
            assertRoundTrip(new RespValue.RespBoolean(false));
            assertRoundTrip(new RespValue.RespDouble(3.14));
            assertRoundTrip(new RespValue.BigNumber(new BigInteger("123456789012345678901234567890")));
            assertRoundTrip(new RespValue.BulkError("SYNTAX bad"));
            assertRoundTrip(new RespValue.VerbatimString("txt", "some text"));
            assertRoundTrip(new RespValue.RespSet(List.of(new RespValue.RespInteger(1),
                    new RespValue.RespInteger(2))));
            assertRoundTrip(new RespValue.RespPush(List.of(RespValue.BulkString.of("pubsub"))));
        }

        @Test
        void mapRoundTrip() {
            RespValue.RespMap map = new RespValue.RespMap(new java.util.LinkedHashMap<>(Map.of(
                    new RespValue.SimpleString("k"), new RespValue.RespInteger(7))));
            assertRoundTrip(map);
        }

        @Test
        void encodedCommandDecodesBackToArrayOfBulkStrings() {
            byte[] wire = codec.encodeCommand("HSET", "h", "field", "value");
            RespValue.RespArray arr = assertInstanceOf(RespValue.RespArray.class, codec.decode(wire));
            assertEquals(List.of(
                    RespValue.BulkString.of("HSET"), RespValue.BulkString.of("h"),
                    RespValue.BulkString.of("field"), RespValue.BulkString.of("value")),
                    arr.items());
        }

        private void assertRoundTrip(RespValue value) {
            assertEquals(value, codec.decode(codec.encode(value)));
        }
    }

    // ------------------------------------------------------------------ input sources & streaming

    @Nested
    class InputSources {

        @Test
        void decodesFromByteBuffer() {
            ByteBuffer buf = ByteBuffer.wrap(bytes("+PONG\r\n"));
            assertEquals(new RespValue.SimpleString("PONG"), codec.decode(buf));
        }

        @Test
        void decodesFromInputStreamAndLeavesRemainderForPipelining() throws Exception {
            InputStream in = new ByteArrayInputStream(bytes(":1\r\n:2\r\n"));
            assertEquals(new RespValue.RespInteger(1), codec.decode(in));
            assertEquals(new RespValue.RespInteger(2), codec.decode(in));
            assertEquals(-1, in.read()); // fully consumed, nothing left
        }
    }

    // ------------------------------------------------------------------ error handling

    @Nested
    class Malformed {

        @Test
        void unknownTypeByteThrows() {
            assertThrows(RespException.class, () -> decode("?nope\r\n"));
        }

        @Test
        void nonNumericLengthThrows() {
            assertThrows(RespException.class, () -> decode("$abc\r\nhello\r\n"));
        }

        @Test
        void invalidBooleanThrows() {
            assertThrows(RespException.class, () -> decode("#x\r\n"));
        }

        @Test
        void missingCrlfAfterBulkBodyThrows() {
            // 5 bytes then a bogus terminator instead of CRLF.
            assertThrows(RespException.class, () -> decode("$5\r\nhelloXX"));
        }

        @Test
        void emptyInputIsIncomplete() {
            assertThrows(RespIncompleteException.class, () -> codec.decode(new byte[0]));
        }

        @Test
        void truncatedBulkIsIncomplete() {
            assertThrows(RespIncompleteException.class, () -> decode("$5\r\nhel"));
        }

        @Test
        void truncatedLineIsIncomplete() {
            assertThrows(RespIncompleteException.class, () -> decode("+OK"));
        }

        @Test
        void incompleteIsARespException() {
            // callers can catch the broad type and treat "need more bytes" specially.
            assertThrows(RespException.class, () -> decode("*2\r\n:1\r\n"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
