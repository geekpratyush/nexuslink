package com.nexuslink.protocol.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dependency-free (JDK-only) codec for the Redis Serialization Protocol. It speaks RESP2 in full
 * and decodes the common RESP3 additions (encoding them too where it is natural).
 *
 * <p><b>Encoding.</b> {@link #encodeCommand(String...)} builds the standard client request form — an
 * array of bulk strings — while {@link #encode(RespValue)} serialises any {@link RespValue} (handy
 * for round-tripping and for writing server-side replies).
 *
 * <p><b>Decoding.</b> {@link #decode(byte[])} / {@link #decode(ByteBuffer)} / {@link
 * #decode(InputStream)} each parse exactly one full reply. The byte/buffer overloads require the
 * reply to be complete: if the bytes run out mid-message they throw {@link
 * RespIncompleteException} so a socket reader can buffer more and retry. The {@link InputStream}
 * overload consumes just the bytes of one reply and leaves the rest of the stream untouched (so a
 * pipeline can be read reply-by-reply); a premature end of stream is likewise reported as
 * {@link RespIncompleteException}. Malformed framing throws {@link RespException}.
 *
 * <p>Text is UTF-8; {@link RespValue.BulkString} payloads are kept as raw bytes and are binary-safe.
 */
public final class RespCodec {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final int MAX_ELEMENTS = 1 << 24; // guard against absurd length prefixes

    // ------------------------------------------------------------------ encode

    /** Encodes a command as an array of bulk strings, e.g. {@code encodeCommand("SET", "k", "v")}. */
    public byte[] encodeCommand(String... args) {
        if (args == null || args.length == 0) {
            throw new RespException("a command needs at least one argument");
        }
        List<byte[]> raw = new ArrayList<>(args.length);
        for (String a : args) {
            if (a == null) {
                throw new RespException("command arguments must not be null");
            }
            raw.add(a.getBytes(StandardCharsets.UTF_8));
        }
        return encodeCommand(raw);
    }

    /** Encodes a command from raw (binary-safe) argument bytes as an array of bulk strings. */
    public byte[] encodeCommand(List<byte[]> args) {
        if (args == null || args.isEmpty()) {
            throw new RespException("a command needs at least one argument");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeHeader(out, '*', args.size());
        for (byte[] arg : args) {
            writeBulkBytes(out, arg);
        }
        return out.toByteArray();
    }

    /** Serialises any {@link RespValue} back to its wire bytes. */
    public byte[] encode(RespValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeInto(out, value);
        return out.toByteArray();
    }

    private void encodeInto(ByteArrayOutputStream out, RespValue value) {
        if (value == null) {
            throw new RespException("cannot encode a null RespValue");
        }
        switch (value) {
            case RespValue.SimpleString v -> writeLine(out, '+', v.value());
            case RespValue.RespError v -> writeLine(out, '-', v.message());
            case RespValue.RespInteger v -> writeLine(out, ':', Long.toString(v.value()));
            case RespValue.BulkString v -> {
                if (v.isNull()) {
                    writeRaw(out, "$-1");
                } else {
                    writeBulkBytes(out, v.bytes());
                }
            }
            case RespValue.RespArray v -> {
                if (v.isNull()) {
                    writeRaw(out, "*-1");
                } else {
                    writeHeader(out, '*', v.items().size());
                    for (RespValue item : v.items()) {
                        encodeInto(out, item);
                    }
                }
            }
            case RespValue.RespNull ignored -> writeRaw(out, "_");
            case RespValue.RespBoolean v -> writeLine(out, '#', v.value() ? "t" : "f");
            case RespValue.RespDouble v -> writeLine(out, ',', formatDouble(v.value()));
            case RespValue.BigNumber v -> writeLine(out, '(', v.value().toString());
            case RespValue.BulkError v -> {
                byte[] msg = v.message().getBytes(StandardCharsets.UTF_8);
                writeHeader(out, '!', msg.length);
                out.write(msg, 0, msg.length);
                out.write(CRLF, 0, CRLF.length);
            }
            case RespValue.VerbatimString v -> {
                if (v.format() == null || v.format().length() != 3) {
                    throw new RespException("verbatim string format must be exactly 3 chars");
                }
                byte[] body = (v.format() + ":" + v.value()).getBytes(StandardCharsets.UTF_8);
                writeHeader(out, '=', body.length);
                out.write(body, 0, body.length);
                out.write(CRLF, 0, CRLF.length);
            }
            case RespValue.RespMap v -> {
                writeHeader(out, '%', v.entries().size());
                for (Map.Entry<RespValue, RespValue> e : v.entries().entrySet()) {
                    encodeInto(out, e.getKey());
                    encodeInto(out, e.getValue());
                }
            }
            case RespValue.RespSet v -> {
                writeHeader(out, '~', v.items().size());
                for (RespValue item : v.items()) {
                    encodeInto(out, item);
                }
            }
            case RespValue.RespPush v -> {
                writeHeader(out, '>', v.items().size());
                for (RespValue item : v.items()) {
                    encodeInto(out, item);
                }
            }
        }
    }

    private static void writeBulkBytes(ByteArrayOutputStream out, byte[] arg) {
        writeHeader(out, '$', arg.length);
        out.write(arg, 0, arg.length);
        out.write(CRLF, 0, CRLF.length);
    }

    private static void writeHeader(ByteArrayOutputStream out, char type, long count) {
        writeRaw(out, type + Long.toString(count));
    }

    private static void writeLine(ByteArrayOutputStream out, char type, String text) {
        writeRaw(out, type + text);
    }

    /** Writes {@code s} followed by CRLF. {@code s} is assumed to be its own line already. */
    private static void writeRaw(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
        out.write(CRLF, 0, CRLF.length);
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d)) return "nan";
        if (d == Double.POSITIVE_INFINITY) return "inf";
        if (d == Double.NEGATIVE_INFINITY) return "-inf";
        return Double.toString(d);
    }

    // ------------------------------------------------------------------ decode

    /** Decodes exactly one reply from {@code data}; throws {@link RespIncompleteException} if short. */
    public RespValue decode(byte[] data) {
        if (data == null) {
            throw new RespException("cannot decode null bytes");
        }
        return decode(new ByteArrayInputStream(data));
    }

    /** Decodes exactly one reply from the buffer, consuming the bytes it reads. */
    public RespValue decode(ByteBuffer buffer) {
        if (buffer == null) {
            throw new RespException("cannot decode a null buffer");
        }
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return decode(data);
    }

    /** Decodes exactly one reply from the stream, consuming only that reply's bytes. */
    public RespValue decode(InputStream in) {
        if (in == null) {
            throw new RespException("cannot decode a null stream");
        }
        try {
            return decodeOne(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RespValue decodeOne(InputStream in) throws IOException {
        int marker = in.read();
        if (marker == -1) {
            throw new RespIncompleteException("no bytes available for a reply");
        }
        return switch ((char) marker) {
            case '+' -> new RespValue.SimpleString(readTextLine(in));
            case '-' -> new RespValue.RespError(readTextLine(in));
            case ':' -> new RespValue.RespInteger(readLong(in));
            case '$' -> decodeBulkString(in);
            case '*' -> decodeArray(in);
            case '_' -> {
                requireEmptyLine(in, "null");
                yield RespValue.RespNull.INSTANCE;
            }
            case '#' -> decodeBoolean(in);
            case ',' -> new RespValue.RespDouble(parseDouble(readTextLine(in)));
            case '(' -> new RespValue.BigNumber(parseBigInteger(readTextLine(in)));
            case '!' -> new RespValue.BulkError(readBulkText(in, "bulk error"));
            case '=' -> decodeVerbatim(in);
            case '%' -> decodeMap(in);
            case '~' -> new RespValue.RespSet(readElements(in, "set"));
            case '>' -> new RespValue.RespPush(readElements(in, "push"));
            default -> throw new RespException(
                    "unknown RESP type byte 0x" + Integer.toHexString(marker & 0xff)
                            + " ('" + (char) marker + "')");
        };
    }

    private RespValue decodeBulkString(InputStream in) throws IOException {
        long len = readLong(in);
        if (len < 0) {
            return RespValue.BulkString.NULL; // $-1
        }
        byte[] body = readExactly(in, len, "bulk string");
        expectCrlf(in, "bulk string");
        return new RespValue.BulkString(body);
    }

    private RespValue decodeArray(InputStream in) throws IOException {
        long count = readLong(in);
        if (count < 0) {
            return new RespValue.RespArray(null); // *-1
        }
        return new RespValue.RespArray(readN(in, count, "array"));
    }

    private RespValue decodeBoolean(InputStream in) throws IOException {
        String s = readTextLine(in);
        return switch (s) {
            case "t" -> new RespValue.RespBoolean(true);
            case "f" -> new RespValue.RespBoolean(false);
            default -> throw new RespException("invalid RESP boolean: '" + s + "'");
        };
    }

    private RespValue decodeVerbatim(InputStream in) throws IOException {
        String body = readBulkText(in, "verbatim string");
        if (body.length() < 4 || body.charAt(3) != ':') {
            throw new RespException("malformed verbatim string: '" + body + "'");
        }
        return new RespValue.VerbatimString(body.substring(0, 3), body.substring(4));
    }

    private RespValue decodeMap(InputStream in) throws IOException {
        long pairs = readLong(in);
        if (pairs < 0 || pairs > MAX_ELEMENTS) {
            throw new RespException("invalid map length: " + pairs);
        }
        Map<RespValue, RespValue> entries = new LinkedHashMap<>();
        for (long i = 0; i < pairs; i++) {
            RespValue key = decodeOne(in);
            RespValue val = decodeOne(in);
            entries.put(key, val);
        }
        return new RespValue.RespMap(entries);
    }

    /** Reads a length-prefixed body (as with bulk strings) and decodes it as UTF-8 text. */
    private String readBulkText(InputStream in, String what) throws IOException {
        long len = readLong(in);
        if (len < 0) {
            throw new RespException(what + " length must not be negative");
        }
        byte[] body = readExactly(in, len, what);
        expectCrlf(in, what);
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Reads a count then that many elements, returning a plain list (for sets/pushes). */
    private List<RespValue> readElements(InputStream in, String what) throws IOException {
        long count = readLong(in);
        if (count < 0) {
            throw new RespException(what + " length must not be negative");
        }
        return readN(in, count, what);
    }

    private List<RespValue> readN(InputStream in, long count, String what) throws IOException {
        if (count > MAX_ELEMENTS) {
            throw new RespException(what + " length too large: " + count);
        }
        List<RespValue> items = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            items.add(decodeOne(in));
        }
        return items;
    }

    // ------------------------------------------------------------------ low-level reads

    private static String readTextLine(InputStream in) throws IOException {
        return new String(readLineBytes(in), StandardCharsets.UTF_8);
    }

    private static long readLong(InputStream in) throws IOException {
        String s = readTextLine(in);
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new RespException("expected an integer, got '" + s + "'");
        }
    }

    private static double parseDouble(String s) {
        return switch (s) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "nan" -> Double.NaN;
            default -> {
                try {
                    yield Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new RespException("invalid RESP double: '" + s + "'");
                }
            }
        };
    }

    private static BigInteger parseBigInteger(String s) {
        try {
            return new BigInteger(s);
        } catch (NumberFormatException e) {
            throw new RespException("invalid RESP big number: '" + s + "'");
        }
    }

    private static void requireEmptyLine(InputStream in, String what) throws IOException {
        byte[] line = readLineBytes(in);
        if (line.length != 0) {
            throw new RespException(what + " must be an empty line, got '"
                    + new String(line, StandardCharsets.UTF_8) + "'");
        }
    }

    /** Reads bytes up to (and consuming) the terminating CRLF; the CRLF is not returned. */
    private static byte[] readLineBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int lf = in.read();
                if (lf == -1) {
                    throw new RespIncompleteException("stream ended after CR before LF");
                }
                if (lf != '\n') {
                    throw new RespException("expected LF after CR, got 0x" + Integer.toHexString(lf & 0xff));
                }
                return buf.toByteArray();
            }
            buf.write(b);
        }
        throw new RespIncompleteException("stream ended before a line terminator (CRLF)");
    }

    private static byte[] readExactly(InputStream in, long len, String what) throws IOException {
        if (len > Integer.MAX_VALUE) {
            throw new RespException(what + " length too large: " + len);
        }
        byte[] body = in.readNBytes((int) len);
        if (body.length < len) {
            throw new RespIncompleteException(
                    "stream ended inside a " + what + " (wanted " + len + ", got " + body.length + ")");
        }
        return body;
    }

    private static void expectCrlf(InputStream in, String what) throws IOException {
        byte[] tail = in.readNBytes(2);
        if (tail.length < 2) {
            throw new RespIncompleteException("stream ended before the CRLF terminating a " + what);
        }
        if (tail[0] != '\r' || tail[1] != '\n') {
            throw new RespException("expected CRLF terminating a " + what);
        }
    }
}
