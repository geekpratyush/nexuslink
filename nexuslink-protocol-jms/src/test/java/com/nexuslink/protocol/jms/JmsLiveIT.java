package com.nexuslink.protocol.jms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live JMS round-trip against the local {@code test-env} ActiveMQ Artemis broker.
 * <pre>docker compose -f test-env/docker-compose.yml up -d artemis</pre>
 * Run with {@code -Dnexuslink.it=true}. Broker: tcp://localhost:61616 (nexus / nexus123).
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class JmsLiveIT {

    private static final String URL = "tcp://localhost:61616";

    @Test
    void sendThenReceive() throws Exception {
        String queue = "nexus.it." + System.currentTimeMillis();
        try (JmsService jms = new JmsService()) {
            jms.connect(URL, "nexus", "nexus123");
            assertTrue(jms.isConnected());

            String id = jms.sendText(queue, "hello-jms");
            assertNotNull(id);

            String body = jms.receiveText(queue, 5000);
            assertEquals("hello-jms", body);
        }
    }

    @Test
    void browseDoesNotConsume() throws Exception {
        String queue = "nexus.browse." + System.currentTimeMillis();
        try (JmsService jms = new JmsService()) {
            jms.connect(URL, "nexus", "nexus123");
            jms.sendText(queue, "peek-me");

            List<JmsService.JmsMessage> peeked = jms.browse(queue, 10);
            assertEquals(1, peeked.size());
            assertEquals("peek-me", peeked.get(0).body());

            // Still there after a browse — a browser must not remove messages.
            assertEquals("peek-me", jms.receiveText(queue, 5000));
        }
    }

    @Test
    void sendWithPropertiesAndTypes() throws Exception {
        String queue = "nexus.props." + System.currentTimeMillis();
        try (JmsService jms = new JmsService()) {
            jms.connect(URL, "nexus", "nexus123");

            // TEXT with custom + standard string properties
            jms.sendMessage(queue, JmsService.MessageType.TEXT, "typed-body",
                    Map.of("region", "eu-west-1", "JMSXGroupID", "g1"));
            JmsService.JmsMessage received = jms.receive(queue, 5000);
            assertNotNull(received);
            assertEquals("Text", received.type());
            assertEquals("typed-body", received.body());
            assertEquals("eu-west-1", received.properties().get("region"));
            assertEquals("g1", received.properties().get("JMSXGroupID"));
        }
    }

    @Test
    void bytesAndMapBodies() throws Exception {
        String queue = "nexus.kinds." + System.currentTimeMillis();
        try (JmsService jms = new JmsService()) {
            jms.connect(URL, "nexus", "nexus123");

            jms.sendMessage(queue, JmsService.MessageType.BYTES, "raw-bytes", Map.of());
            jms.sendMessage(queue, JmsService.MessageType.MAP, "a=1\nb=two", Map.of());

            List<JmsService.JmsMessage> peeked = jms.browse(queue, 10);
            assertEquals(2, peeked.size());
            assertEquals("Bytes", peeked.get(0).type());
            assertEquals("raw-bytes", peeked.get(0).body());
            assertEquals("Map", peeked.get(1).type());
            assertTrue(peeked.get(1).body().contains("a=1"));
            assertTrue(peeked.get(1).body().contains("b=two"));
        }
    }
}
