package com.nexuslink.protocol.mqtt;

import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;

import java.util.List;

/**
 * Renders an MQTT publish/subscribe snippet — the MQTT side of the cross-protocol code-generation
 * SPI. The broker URI is parsed with {@link MqttBrokerUri}, so a {@code ssl://} or {@code ws://}
 * endpoint produces the right host/port/TLS flags in every target, and no password is ever written
 * into a snippet (a placeholder is emitted instead).
 */
public final class MqttCodeGenerator implements CodeGenerator {

    /**
     * What to generate for.
     *
     * @param brokerUri e.g. {@code tcp://localhost:1883} (blank → that default)
     * @param topic     the topic to publish to / subscribe to
     * @param qos       0, 1 or 2
     * @param retained  whether the publish sets the retained flag
     * @param username  optional; when set the snippets show where credentials go
     */
    public record Request(String brokerUri, String topic, int qos, boolean retained, String username) {

        public Request {
            brokerUri = (brokerUri == null || brokerUri.isBlank()) ? "tcp://localhost:1883" : brokerUri.trim();
            topic = (topic == null || topic.isBlank()) ? "my/topic" : topic.trim();
            username = username == null ? "" : username.trim();
            qos = Math.max(0, Math.min(2, qos));
        }

        /** An anonymous QoS-0 request. */
        public static Request of(String brokerUri, String topic) {
            return new Request(brokerUri, topic, 0, false, "");
        }
    }

    private static final CodeGenTarget MOSQUITTO =
            new CodeGenTarget("mosquitto", "CLI (mosquitto_pub/sub)", "shell");
    private static final CodeGenTarget JAVA =
            new CodeGenTarget("java", "Java (Paho v5)", "java");
    private static final CodeGenTarget PYTHON =
            new CodeGenTarget("python", "Python (paho-mqtt)", "python");
    private static final CodeGenTarget NODE =
            new CodeGenTarget("node", "Node.js (mqtt.js)", "javascript");

    private static final List<CodeGenTarget> TARGETS = List.of(MOSQUITTO, JAVA, PYTHON, NODE);

    @Override
    public String protocolId() {
        return "mqtt";
    }

    @Override
    public String displayName() {
        return "MQTT";
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
            throw new IllegalArgumentException("not an MQTT code-gen request: " + request);
        }
        MqttBrokerUri uri = MqttBrokerUri.parse(r.brokerUri());
        if (MOSQUITTO.id().equals(target.id())) return mosquitto(r, uri);
        if (JAVA.id().equals(target.id())) return java(r, uri);
        if (PYTHON.id().equals(target.id())) return python(r, uri);
        if (NODE.id().equals(target.id())) return node(r, uri);
        throw new IllegalArgumentException("unknown MQTT code-gen target: " + target.id());
    }

    // ------------------------------------------------------------------ renderers

    private static String mosquitto(Request r, MqttBrokerUri uri) {
        String auth = r.username().isBlank() ? "" : " -u " + r.username() + " -P '<password>'";
        String tls = uri.tls() ? " --capath /etc/ssl/certs" : "";
        String retained = r.retained() ? " -r" : "";
        return """
                # publish
                mosquitto_pub -h %s -p %d%s%s -t '%s' -q %d%s -m 'hello'

                # subscribe (Ctrl-C to stop)
                mosquitto_sub -h %s -p %d%s%s -t '%s' -q %d -v
                """.formatted(uri.host(), uri.port(), auth, tls, r.topic(), r.qos(), retained,
                uri.host(), uri.port(), auth, tls, r.topic(), r.qos());
    }

    private static String java(Request r, MqttBrokerUri uri) {
        String auth = r.username().isBlank() ? "" : """
                options.setUserName("%s");
                options.setPassword("<password>".getBytes(StandardCharsets.UTF_8));
                """.formatted(r.username());
        return """
                // Eclipse Paho v5 — org.eclipse.paho:org.eclipse.paho.mqttv5.client
                MqttClient client = new MqttClient("%s", MqttClient.generateClientId(), new MemoryPersistence());
                MqttConnectionOptions options = new MqttConnectionOptions();
                options.setCleanStart(true);
                %sclient.connect(options);

                // subscribe
                client.setCallback(/* an MqttCallback printing message.toString() */ null);
                client.subscribe("%s", %d);

                // publish
                MqttMessage message = new MqttMessage("hello".getBytes(StandardCharsets.UTF_8));
                message.setQos(%d);
                message.setRetained(%s);
                client.publish("%s", message);
                """.formatted(uri.redacted(), auth, r.topic(), r.qos(), r.qos(), r.retained(), r.topic());
    }

    private static String python(Request r, MqttBrokerUri uri) {
        String auth = r.username().isBlank() ? "" :
                "client.username_pw_set(\"" + r.username() + "\", \"<password>\")\n";
        String tls = uri.tls() ? "client.tls_set()\n" : "";
        return """
                # pip install paho-mqtt
                import paho.mqtt.client as mqtt

                client = mqtt.Client()
                %s%sclient.connect("%s", %d)

                client.publish("%s", "hello", qos=%d, retain=%s)

                client.on_message = lambda c, u, msg: print(msg.topic, msg.payload.decode())
                client.subscribe("%s", qos=%d)
                client.loop_forever()
                """.formatted(auth, tls, uri.host(), uri.port(),
                r.topic(), r.qos(), r.retained() ? "True" : "False", r.topic(), r.qos());
    }

    private static String node(Request r, MqttBrokerUri uri) {
        String auth = r.username().isBlank() ? "" :
                ", username: '" + r.username() + "', password: '<password>'";
        return """
                // npm install mqtt
                const mqtt = require('mqtt');

                const client = mqtt.connect('%s', { clean: true%s });

                client.on('connect', () => {
                  client.publish('%s', 'hello', { qos: %d, retain: %s });
                  client.subscribe('%s', { qos: %d });
                });

                client.on('message', (topic, payload) => console.log(topic, payload.toString()));
                """.formatted(uri.redacted(), auth, r.topic(), r.qos(), r.retained(), r.topic(), r.qos());
    }
}
