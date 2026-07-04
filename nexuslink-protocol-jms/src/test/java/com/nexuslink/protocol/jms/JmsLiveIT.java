package com.nexuslink.protocol.jms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

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
}
