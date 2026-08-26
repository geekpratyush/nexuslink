package com.nexuslink.protocol.kafka;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The five-byte header Confluent's serializers put in front of every schema-registered payload:
 * a zero magic byte, then the schema id as a four-byte big-endian integer, then the encoded value.
 *
 * <p>Reading it is the whole trick to browsing an Avro topic: the payload alone is undecodable —
 * Avro binary carries no field names — but the id says which schema to fetch, and with the schema the
 * bytes become readable. A client that does not do this shows Avro topics as mojibake, which is what
 * "the Schema Registry tab is informational only" really means.
 *
 * <p>Protobuf adds a message-index array after the id, so its payload offset is not fixed; that is
 * parsed here too.
 *
 * <p>Pure: parsing and framing are byte arithmetic, testable without a registry or a broker.
 */
public final class ConfluentWireFormat {

    /** The magic byte that marks a schema-registry framed payload. */
    public static final byte MAGIC = 0x0;

    /** The header length for Avro and JSON Schema: magic byte plus a four-byte id. */
    public static final int HEADER_BYTES = 5;

    private ConfluentWireFormat() {}

    /** A framed payload: the schema id and where the encoded value starts. */
    public record Framed(int schemaId, int payloadOffset, byte[] payload) {

        /** The payload as UTF-8 text — right for JSON Schema, meaningless for Avro binary. */
        public String payloadAsText() {
            return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code true} when {@code data} looks like a registry-framed payload: at least five bytes and a
     * zero magic byte. A plain JSON or string payload starts with something else, so this is a safe
     * test to run on every record.
     */
    public static boolean isFramed(byte[] data) {
        return data != null && data.length >= HEADER_BYTES && data[0] == MAGIC;
    }

    /**
     * Reads the frame.
     *
     * @param messageIndexes {@code true} for Protobuf, whose header carries a message-index array
     *                       after the schema id
     * @return the frame, or {@code null} when {@code data} is not framed
     */
    public static Framed parse(byte[] data, boolean messageIndexes) {
        if (!isFramed(data)) return null;
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.get();                       // magic
        int schemaId = buffer.getInt();
        if (messageIndexes) skipMessageIndexes(buffer);
        int offset = buffer.position();
        byte[] payload = new byte[data.length - offset];
        System.arraycopy(data, offset, payload, 0, payload.length);
        return new Framed(schemaId, offset, payload);
    }

    /** Avro and JSON Schema framing — no message indexes. */
    public static Framed parse(byte[] data) {
        return parse(data, false);
    }

    /**
     * Skips Protobuf's message-index array: a zigzag varint count, then that many zigzag varints.
     * A single zero byte is the shorthand for "the first message", which is the common case.
     */
    private static void skipMessageIndexes(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) return;
        int start = buffer.position();
        long count = readZigZagVarint(buffer);
        if (count == 0) return;                      // shorthand: index [0]
        if (count < 0 || count > 100) {              // not an index array after all — rewind
            buffer.position(start);
            return;
        }
        for (int i = 0; i < count && buffer.hasRemaining(); i++) readZigZagVarint(buffer);
    }

    /** Reads one zigzag-encoded varint (Protobuf's signed integer encoding). */
    private static long readZigZagVarint(ByteBuffer buffer) {
        long raw = 0;
        int shift = 0;
        while (buffer.hasRemaining() && shift < 64) {
            byte b = buffer.get();
            raw |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return (raw >>> 1) ^ -(raw & 1);
    }

    /** Frames {@code payload} with {@code schemaId} — the producing side of the same format. */
    public static byte[] frame(int schemaId, byte[] payload) {
        byte[] out = new byte[HEADER_BYTES + payload.length];
        ByteBuffer.wrap(out).put(MAGIC).putInt(schemaId).put(payload);
        return out;
    }

    /** The schema ids seen across a batch of payloads, in first-seen order — for the browser's header. */
    public static List<Integer> schemaIdsOf(List<byte[]> payloads) {
        List<Integer> ids = new ArrayList<>();
        if (payloads == null) return ids;
        for (byte[] payload : payloads) {
            Framed framed = parse(payload);
            if (framed != null && !ids.contains(framed.schemaId())) ids.add(framed.schemaId());
        }
        return ids;
    }
}
