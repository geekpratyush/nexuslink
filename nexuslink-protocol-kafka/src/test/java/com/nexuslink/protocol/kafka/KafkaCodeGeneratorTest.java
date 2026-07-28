package com.nexuslink.protocol.kafka;

import com.nexuslink.plugin.codegen.CodeGenRegistry;
import com.nexuslink.plugin.codegen.CodeGenTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaCodeGeneratorTest {

    private final KafkaCodeGenerator gen = new KafkaCodeGenerator();

    private String render(String targetId, KafkaCodeGenerator.Request request) {
        return gen.generate(gen.targetById(targetId), request);
    }

    @Test
    @DisplayName("the producer snippet carries the bootstrap servers and topic")
    void javaProducer() {
        String code = render("java-producer", KafkaCodeGenerator.Request.of("kafka:9092", "orders"));

        assertTrue(code.contains("\"bootstrap.servers\", \"kafka:9092\""), code);
        assertTrue(code.contains("new ProducerRecord<>(\"orders\""), code);
    }

    @Test
    @DisplayName("the consumer snippet carries the group id")
    void javaConsumer() {
        String code = render("java-consumer",
                new KafkaCodeGenerator.Request("kafka:9092", "orders", "billing", "", ""));

        assertTrue(code.contains("\"group.id\", \"billing\""), code);
        assertTrue(code.contains("List.of(\"orders\")"), code);
    }

    @Test
    @DisplayName("security properties appear only when the cluster is not plaintext")
    void securityOnlyWhenSecured() {
        String plain = render("java-producer",
                new KafkaCodeGenerator.Request("kafka:9092", "t", "", "PLAINTEXT", ""));
        String secured = render("java-producer",
                new KafkaCodeGenerator.Request("kafka:9092", "t", "", "SASL_SSL", "SCRAM-SHA-512"));

        assertFalse(plain.contains("security.protocol"), "plaintext must stay clean: " + plain);
        assertTrue(secured.contains("\"security.protocol\", \"SASL_SSL\""), secured);
        assertTrue(secured.contains("\"sasl.mechanism\", \"SCRAM-SHA-512\""), secured);
    }

    @Test
    @DisplayName("the CLI snippet notes the security config instead of inventing one")
    void cliSecurityNote() {
        String code = render("cli", new KafkaCodeGenerator.Request("kafka:9092", "t", "g", "SASL_SSL", "PLAIN"));

        assertTrue(code.contains("--producer.config"), code);
        assertTrue(code.contains("security.protocol=SASL_SSL"), code);
        assertTrue(code.contains("--group g"), code);
    }

    @Test
    @DisplayName("blank inputs fall back to runnable defaults")
    void requestDefaults() {
        KafkaCodeGenerator.Request request = new KafkaCodeGenerator.Request(null, "", null, null, null);

        assertEquals("localhost:9092", request.bootstrapServers());
        assertEquals("my-topic", request.topic());
        assertEquals("nexuslink-demo", request.groupId());
        assertFalse(request.isSecured());
    }

    @Test
    @DisplayName("every target renders non-empty code and bad input is rejected")
    void targetsAndGuards() {
        KafkaCodeGenerator.Request request = KafkaCodeGenerator.Request.of("kafka:9092", "t");

        assertEquals(4, gen.targets().size());
        for (CodeGenTarget target : gen.targets()) {
            assertFalse(gen.generate(target, request).isBlank(), target.id() + " rendered nothing");
        }
        assertThrows(IllegalArgumentException.class,
                () -> gen.generate(new CodeGenTarget("brainfuck", "BF", "text"), request));
        assertThrows(IllegalArgumentException.class, () -> gen.generate(gen.targets().get(0), 42));
    }

    @Test
    @DisplayName("the generator is discoverable through the SPI registry")
    void discoverableViaServiceLoader() {
        CodeGenRegistry registry = CodeGenRegistry.load(KafkaCodeGenerator.class.getClassLoader());

        assertEquals("Kafka", registry.byProtocol("kafka").orElseThrow().displayName());
    }
}
