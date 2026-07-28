package com.nexuslink.protocol.kafka;

import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;

import java.util.List;

/**
 * Renders a Kafka producer/consumer snippet for a topic on a cluster — the Kafka side of the
 * cross-protocol code-generation SPI.
 *
 * <p>The input is a {@link Request}: bootstrap servers, topic, an optional consumer group and the
 * security protocol the view is configured with. Snippets are deliberately runnable as-is against a
 * plaintext cluster and carry the security properties only when the request asks for them, so what
 * you copy matches what the client actually does.
 */
public final class KafkaCodeGenerator implements CodeGenerator {

    /**
     * What to generate for.
     *
     * @param bootstrapServers the {@code host:port[,host:port]} list
     * @param topic            the topic to produce to / consume from
     * @param groupId          consumer group for the consumer snippets (blank → {@code nexuslink-demo})
     * @param securityProtocol e.g. {@code PLAINTEXT}, {@code SASL_SSL} (blank → plaintext, omitted)
     * @param saslMechanism    e.g. {@code PLAIN}, {@code SCRAM-SHA-512} (only used when secured)
     */
    public record Request(String bootstrapServers, String topic, String groupId,
                          String securityProtocol, String saslMechanism) {

        public Request {
            bootstrapServers = blankTo(bootstrapServers, "localhost:9092");
            topic = blankTo(topic, "my-topic");
            groupId = blankTo(groupId, "nexuslink-demo");
            securityProtocol = securityProtocol == null ? "" : securityProtocol.trim();
            saslMechanism = saslMechanism == null ? "" : saslMechanism.trim();
        }

        /** A plaintext request for {@code topic} on {@code bootstrapServers}. */
        public static Request of(String bootstrapServers, String topic) {
            return new Request(bootstrapServers, topic, "", "", "");
        }

        /** {@code true} when the cluster needs security properties in the snippet. */
        public boolean isSecured() {
            return !securityProtocol.isBlank() && !securityProtocol.equalsIgnoreCase("PLAINTEXT");
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private static final CodeGenTarget JAVA_PRODUCER =
            new CodeGenTarget("java-producer", "Java producer", "java");
    private static final CodeGenTarget JAVA_CONSUMER =
            new CodeGenTarget("java-consumer", "Java consumer", "java");
    private static final CodeGenTarget PYTHON =
            new CodeGenTarget("python", "Python (kafka-python)", "python");
    private static final CodeGenTarget CLI =
            new CodeGenTarget("cli", "CLI (kafka-console-*)", "shell");

    private static final List<CodeGenTarget> TARGETS = List.of(JAVA_PRODUCER, JAVA_CONSUMER, PYTHON, CLI);

    @Override
    public String protocolId() {
        return "kafka";
    }

    @Override
    public String displayName() {
        return "Kafka";
    }

    @Override
    public boolean supports(Object request) {
        return request instanceof Request;
    }

    @Override
    public List<CodeGenTarget> targets() {
        return TARGETS;
    }

    @Override
    public String generate(CodeGenTarget target, Object request) {
        if (!(request instanceof Request r)) {
            throw new IllegalArgumentException("not a Kafka code-gen request: " + request);
        }
        if (JAVA_PRODUCER.id().equals(target.id())) return javaProducer(r);
        if (JAVA_CONSUMER.id().equals(target.id())) return javaConsumer(r);
        if (PYTHON.id().equals(target.id())) return python(r);
        if (CLI.id().equals(target.id())) return cli(r);
        throw new IllegalArgumentException("unknown Kafka code-gen target: " + target.id());
    }

    // ------------------------------------------------------------------ renderers

    private static String javaSecurity(Request r) {
        if (!r.isSecured()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("props.put(\"security.protocol\", \"").append(r.securityProtocol()).append("\");\n");
        if (!r.saslMechanism().isBlank()) {
            sb.append("props.put(\"sasl.mechanism\", \"").append(r.saslMechanism()).append("\");\n");
            sb.append("props.put(\"sasl.jaas.config\", \"<jaas config — see the Security tab>\");\n");
        }
        return sb.toString();
    }

    private static String javaProducer(Request r) {
        return """
                // Kafka producer — org.apache.kafka:kafka-clients
                Properties props = new Properties();
                props.put("bootstrap.servers", "%s");
                props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
                %s
                try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                    RecordMetadata md = producer
                            .send(new ProducerRecord<>("%s", "key", "value"))
                            .get();
                    System.out.println("sent to " + md.partition() + "@" + md.offset());
                }
                """.formatted(r.bootstrapServers(), javaSecurity(r), r.topic());
    }

    private static String javaConsumer(Request r) {
        return """
                // Kafka consumer — org.apache.kafka:kafka-clients
                Properties props = new Properties();
                props.put("bootstrap.servers", "%s");
                props.put("group.id", "%s");
                props.put("auto.offset.reset", "earliest");
                props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
                props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
                %s
                try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                    consumer.subscribe(List.of("%s"));
                    while (true) {
                        for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofSeconds(1))) {
                            System.out.printf("%%d@%%d %%s%%n", rec.partition(), rec.offset(), rec.value());
                        }
                    }
                }
                """.formatted(r.bootstrapServers(), r.groupId(), javaSecurity(r), r.topic());
    }

    private static String python(Request r) {
        String security = r.isSecured()
                ? ",\n    security_protocol=\"" + r.securityProtocol() + "\""
                + (r.saslMechanism().isBlank() ? "" : ",\n    sasl_mechanism=\"" + r.saslMechanism() + "\"")
                : "";
        return """
                # pip install kafka-python
                from kafka import KafkaProducer, KafkaConsumer

                producer = KafkaProducer(
                    bootstrap_servers="%s"%s)
                producer.send("%s", b"value")
                producer.flush()

                consumer = KafkaConsumer(
                    "%s",
                    bootstrap_servers="%s",
                    group_id="%s",
                    auto_offset_reset="earliest"%s)
                for record in consumer:
                    print(record.partition, record.offset, record.value)
                """.formatted(r.bootstrapServers(), security, r.topic(),
                r.topic(), r.bootstrapServers(), r.groupId(), security);
    }

    private static String cli(Request r) {
        String securityNote = r.isSecured()
                ? "# secured cluster: add --producer.config/--consumer.config with\n"
                + "#   security.protocol=" + r.securityProtocol()
                + (r.saslMechanism().isBlank() ? "" : "\n#   sasl.mechanism=" + r.saslMechanism()) + "\n"
                : "";
        return """
                %s# produce (type a message, then Ctrl-D)
                kafka-console-producer.sh --bootstrap-server %s --topic %s

                # consume from the beginning
                kafka-console-consumer.sh --bootstrap-server %s --topic %s \\
                    --group %s --from-beginning
                """.formatted(securityNote, r.bootstrapServers(), r.topic(),
                r.bootstrapServers(), r.topic(), r.groupId());
    }
}
