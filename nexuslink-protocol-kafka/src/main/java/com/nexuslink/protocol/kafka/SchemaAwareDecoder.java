package com.nexuslink.protocol.kafka;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Turns a Schema-Registry-framed payload into readable text: reads the schema id from the frame,
 * fetches that schema (once, then cached), and decodes the value with it.
 *
 * <p>Avro is the case that matters. Its binary encoding carries no field names, so without the
 * schema the bytes are unreadable — which is why an Avro topic browses as mojibake in a client that
 * ignores the registry. With the schema, the same bytes render as JSON. JSON Schema payloads are
 * already JSON behind the frame; Protobuf needs the compiled descriptor to decode fully, so it is
 * reported honestly as framed-but-not-decoded rather than being shown as garbage.
 *
 * <p>The schema lookup is a plain {@code int → String} function, so this class can be tested with a
 * stub and used with the real {@link SchemaRegistryClient} unchanged.
 */
public final class SchemaAwareDecoder {

    /** What a payload turned out to be. */
    public enum Kind {
        /** Not framed — decoded as plain text, as before. */
        PLAIN,
        /** Framed and decoded with its Avro schema. */
        AVRO,
        /** Framed JSON Schema payload — the JSON behind the header. */
        JSON_SCHEMA,
        /** Framed Protobuf — the id is known, the payload needs the descriptor to decode. */
        PROTOBUF,
        /** Framed, but the schema could not be fetched or applied. */
        UNDECODED
    }

    /** The decoded text, what kind of payload it was, and the schema id when there was one. */
    public record Decoded(String text, Kind kind, int schemaId, String note) {

        /** {@code true} when the registry actually contributed to the rendering. */
        public boolean usedSchema() { return kind == Kind.AVRO || kind == Kind.JSON_SCHEMA; }

        /** A label for the browser's status line, e.g. {@code Avro · schema 7}. */
        public String label() {
            return switch (kind) {
                case PLAIN -> "plain";
                case AVRO -> "Avro · schema " + schemaId;
                case JSON_SCHEMA -> "JSON Schema · schema " + schemaId;
                case PROTOBUF -> "Protobuf · schema " + schemaId;
                case UNDECODED -> "framed · schema " + schemaId + " (" + note + ")";
            };
        }
    }

    private final IntFunction<String> schemaById;
    private final Map<Integer, Schema> parsedSchemas = new ConcurrentHashMap<>();

    /** Uses a live registry client. */
    public SchemaAwareDecoder(SchemaRegistryClient registry) {
        this(id -> {
            try {
                return registry.getSchemaById(id);
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** Uses any schema source — the seam the tests drive. */
    public SchemaAwareDecoder(IntFunction<String> schemaById) {
        this.schemaById = schemaById;
    }

    /**
     * Decodes one payload. An unframed payload comes back as its own text, so this can be applied to
     * every record without knowing in advance whether the topic uses the registry.
     */
    public Decoded decode(byte[] data) {
        if (data == null) return new Decoded("", Kind.PLAIN, -1, "");
        ConfluentWireFormat.Framed framed = ConfluentWireFormat.parse(data);
        if (framed == null) {
            return new Decoded(new String(data, StandardCharsets.UTF_8), Kind.PLAIN, -1, "");
        }

        String schemaText = schemaById.apply(framed.schemaId());
        if (schemaText == null || schemaText.isBlank()) {
            return new Decoded(framed.payloadAsText(), Kind.UNDECODED, framed.schemaId(),
                    "schema not found in the registry");
        }

        String trimmed = schemaText.trim();
        // A JSON Schema document declares $schema or type at the top level; an Avro schema is a
        // record/union definition. Telling them apart by shape avoids needing the registry's
        // schemaType field, which older registries do not return.
        if (trimmed.contains("\"$schema\"") || trimmed.startsWith("{\"type\":\"object\"")) {
            return new Decoded(framed.payloadAsText(), Kind.JSON_SCHEMA, framed.schemaId(), "");
        }
        if (trimmed.startsWith("syntax") || trimmed.contains("message ")) {
            return new Decoded(framed.payloadAsText(), Kind.PROTOBUF, framed.schemaId(),
                    "Protobuf needs the compiled descriptor to decode");
        }

        try {
            Schema schema = parsedSchemas.computeIfAbsent(framed.schemaId(),
                    id -> new Schema.Parser().parse(schemaText));
            return new Decoded(toJson(schema, framed.payload()), Kind.AVRO, framed.schemaId(), "");
        } catch (Exception e) {
            return new Decoded(framed.payloadAsText(), Kind.UNDECODED, framed.schemaId(),
                    "could not decode with that schema: " + e.getMessage());
        }
    }

    /** Decodes Avro binary into JSON using {@code schema}. */
    private static String toJson(Schema schema, byte[] payload) throws java.io.IOException {
        DatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null, DecoderFactory.get().binaryDecoder(payload, null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var encoder = org.apache.avro.io.EncoderFactory.get().jsonEncoder(schema, out, true);
        new org.apache.avro.generic.GenericDatumWriter<GenericRecord>(schema).write(record, encoder);
        encoder.flush();
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Encodes JSON as Avro binary against {@code schemaText} and frames it — the producing side. */
    public static byte[] encodeAvro(String schemaText, int schemaId, String json) throws java.io.IOException {
        Schema schema = new Schema.Parser().parse(schemaText);
        GenericDatumReader<GenericRecord> reader = new GenericDatumReader<>(schema);
        GenericRecord record = reader.read(null,
                DecoderFactory.get().jsonDecoder(schema, json));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var encoder = org.apache.avro.io.EncoderFactory.get().binaryEncoder(out, null);
        new org.apache.avro.generic.GenericDatumWriter<GenericRecord>(schema).write(record, encoder);
        encoder.flush();
        return ConfluentWireFormat.frame(schemaId, out.toByteArray());
    }
}
