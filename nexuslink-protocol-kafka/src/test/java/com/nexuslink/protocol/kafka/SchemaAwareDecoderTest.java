package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SchemaAwareDecoderTest {

    private static final String AVRO_SCHEMA = """
            {"type":"record","name":"Order","fields":[
              {"name":"id","type":"int"},
              {"name":"customer","type":"string"},
              {"name":"total","type":"double"}]}""";

    private static SchemaAwareDecoder decoderFor(String schema) {
        return new SchemaAwareDecoder(id -> id == 7 ? schema : null);
    }

    @Test
    void anAvroPayloadDecodesToJsonUsingItsRegisteredSchema() throws Exception {
        byte[] framed = SchemaAwareDecoder.encodeAvro(AVRO_SCHEMA, 7,
                "{\"id\":1,\"customer\":\"ada\",\"total\":9.5}");
        SchemaAwareDecoder.Decoded decoded = decoderFor(AVRO_SCHEMA).decode(framed);

        assertEquals(SchemaAwareDecoder.Kind.AVRO, decoded.kind());
        assertEquals(7, decoded.schemaId());
        assertTrue(decoded.usedSchema());
        assertTrue(decoded.text().replace(" ", "").contains("\"customer\":\"ada\""), decoded.text());
        assertTrue(decoded.text().contains("9.5"), decoded.text());
        assertEquals("Avro · schema 7", decoded.label());
    }

    @Test
    void withoutTheSchemaTheSameBytesAreUnreadableWhichIsWhyThisExists() throws Exception {
        byte[] framed = SchemaAwareDecoder.encodeAvro(AVRO_SCHEMA, 7, "{\"id\":1,\"customer\":\"ada\",\"total\":9.5}");
        String raw = new String(framed, StandardCharsets.UTF_8);
        assertFalse(raw.contains("customer"), "Avro binary carries no field names — that is the point");
    }

    @Test
    void aMissingSchemaIsReportedRatherThanGuessed() throws Exception {
        byte[] framed = SchemaAwareDecoder.encodeAvro(AVRO_SCHEMA, 99, "{\"id\":1,\"customer\":\"a\",\"total\":1}");
        SchemaAwareDecoder.Decoded decoded = decoderFor(AVRO_SCHEMA).decode(framed);
        assertEquals(SchemaAwareDecoder.Kind.UNDECODED, decoded.kind());
        assertEquals(99, decoded.schemaId());
        assertTrue(decoded.note().contains("not found"), decoded.note());
        assertFalse(decoded.usedSchema());
    }

    @Test
    void aWrongSchemaIsReportedRatherThanRenderedAsNonsense() {
        byte[] framed = ConfluentWireFormat.frame(7, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        SchemaAwareDecoder.Decoded decoded = decoderFor(AVRO_SCHEMA).decode(framed);
        assertEquals(SchemaAwareDecoder.Kind.UNDECODED, decoded.kind());
        assertTrue(decoded.note().contains("could not decode"), decoded.note());
    }

    @Test
    void anUnframedPayloadIsLeftAsPlainText() {
        SchemaAwareDecoder.Decoded decoded = decoderFor(AVRO_SCHEMA)
                .decode("{\"plain\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(SchemaAwareDecoder.Kind.PLAIN, decoded.kind());
        assertEquals("{\"plain\":true}", decoded.text());
        assertEquals(-1, decoded.schemaId());
    }

    @Test
    void aJsonSchemaPayloadIsTheJsonBehindTheHeader() {
        String jsonSchema = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}";
        byte[] framed = ConfluentWireFormat.frame(7, "{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        SchemaAwareDecoder.Decoded decoded = new SchemaAwareDecoder(id -> jsonSchema).decode(framed);
        assertEquals(SchemaAwareDecoder.Kind.JSON_SCHEMA, decoded.kind());
        assertEquals("{\"id\":1}", decoded.text());
        assertTrue(decoded.usedSchema());
    }

    @Test
    void aProtobufSchemaIsRecognisedAndSaidToNeedItsDescriptor() {
        byte[] framed = ConfluentWireFormat.frame(7, new byte[]{1, 2, 3});
        SchemaAwareDecoder.Decoded decoded = new SchemaAwareDecoder(
                id -> "syntax = \"proto3\"; message Order { int32 id = 1; }").decode(framed);
        assertEquals(SchemaAwareDecoder.Kind.PROTOBUF, decoded.kind());
        assertTrue(decoded.label().contains("Protobuf"), decoded.label());
        assertFalse(decoded.usedSchema(), "we frame it honestly rather than pretending to decode");
    }

    @Test
    void aNullPayloadIsEmptyPlainText() {
        SchemaAwareDecoder.Decoded decoded = decoderFor(AVRO_SCHEMA).decode(null);
        assertEquals(SchemaAwareDecoder.Kind.PLAIN, decoded.kind());
        assertEquals("", decoded.text());
    }

    @Test
    void theSchemaIsFetchedOnceAndReusedForEveryRecord() throws Exception {
        int[] fetches = {0};
        SchemaAwareDecoder decoder = new SchemaAwareDecoder(id -> { fetches[0]++; return AVRO_SCHEMA; });
        byte[] framed = SchemaAwareDecoder.encodeAvro(AVRO_SCHEMA, 7, "{\"id\":1,\"customer\":\"a\",\"total\":1}");
        for (int i = 0; i < 5; i++) decoder.decode(framed);
        assertEquals(5, fetches[0], "the registry lookup itself is the caller's to cache");
        assertEquals(SchemaAwareDecoder.Kind.AVRO, decoder.decode(framed).kind(),
                "the parsed schema is cached, so repeated decodes stay cheap");
    }
}
