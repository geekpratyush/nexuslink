package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema-aware browsing against the real broker plus the Confluent-compatible registry in
 * {@code test-env}: an Avro record produced with the registry's schema id, then browsed back as JSON.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class KafkaSchemaLiveIT {

    private static final String REGISTRY = "http://localhost:8081/apis/ccompat/v7";
    private static final String AVRO_SCHEMA = """
            {"type":"record","name":"Order","namespace":"nexuslink.it","fields":[
              {"name":"id","type":"int"},
              {"name":"customer","type":"string"}]}""";

    private KafkaService service;
    private SchemaRegistryClient registry;
    private String topic;
    private String subject;

    @BeforeEach
    void setUp() throws Exception {
        service = new KafkaService();
        service.connect("localhost:9092", Map.of());
        registry = new SchemaRegistryClient(REGISTRY);
        topic = "nexuslink-avro-it-" + System.nanoTime();
        subject = topic + "-value";
        service.createTopic(topic, 1, (short) 1);
    }

    @AfterEach
    void tearDown() {
        try { service.deleteTopic(topic); } catch (Exception ignored) { }
        service.close();
    }

    @Test
    void anAvroRecordBrowsesAsJsonInsteadOfMojibake() throws Exception {
        int schemaId = registry.register(subject, AVRO_SCHEMA);
        assertTrue(schemaId > 0, "the registry assigned a schema id");

        // Produce the Avro-encoded, registry-framed bytes exactly as a Confluent serializer would.
        byte[] framed = SchemaAwareDecoder.encodeAvro(AVRO_SCHEMA, schemaId,
                "{\"id\":42,\"customer\":\"ada\"}");
        service.send(ProduceSpec.of(topic, "k",
                new String(framed, java.nio.charset.StandardCharsets.ISO_8859_1)));

        // Without the registry the value is unreadable — this is the status quo this feature fixes.
        List<KafkaService.KafkaMessage> plain = service.browse(topic, 5, true);
        assertEquals(1, plain.size());
        assertFalse(plain.get(0).value().contains("customer"), "Avro binary carries no field names");

        service.useSchemaRegistry(registry);
        assertTrue(service.isSchemaAware());
        List<KafkaService.KafkaMessage> decoded = service.browseDecoded(topic, 5, true);
        assertEquals(1, decoded.size());
        String value = decoded.get(0).value().replace(" ", "");
        assertTrue(value.contains("\"customer\":\"ada\""), decoded.get(0).value());
        assertTrue(value.contains("\"id\":42"), decoded.get(0).value());
        assertTrue(decoded.get(0).headerText().contains("Avro · schema " + schemaId),
                decoded.get(0).headerText());
    }

    @Test
    void aPlainTopicStillBrowsesWhenTheRegistryIsAttached() throws Exception {
        service.send(ProduceSpec.of(topic, "k", "{\"plain\":true}"));
        service.useSchemaRegistry(registry);
        List<KafkaService.KafkaMessage> decoded = service.browseDecoded(topic, 5, true);
        assertEquals(1, decoded.size());
        assertEquals("{\"plain\":true}", decoded.get(0).value(),
                "an unframed record must read exactly as before");
        assertTrue(decoded.get(0).headers().isEmpty(), "no schema header for a plain record");
    }

    @Test
    void detachingTheRegistryGoesBackToPlainReads() {
        service.useSchemaRegistry(registry);
        assertTrue(service.isSchemaAware());
        service.useSchemaRegistry(null);
        assertFalse(service.isSchemaAware());
    }

    @Test
    void theRegistryRoundTripsTheSchemaById() throws Exception {
        int id = registry.register(subject, AVRO_SCHEMA);
        String fetched = registry.getSchemaById(id);
        assertNotNull(fetched);
        assertTrue(fetched.contains("customer"), fetched);
    }
}
