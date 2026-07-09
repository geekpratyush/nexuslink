package com.nexuslink.protocol.pubsub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Pub/Sub test against the local {@code test-env} Google Cloud Pub/Sub emulator.
 * <p>
 * The Google client talks to the emulator via the {@code PUBSUB_EMULATOR_HOST} env var, so run:
 * <pre>docker compose -f test-env/docker-compose.yml up -d pubsub
 * PUBSUB_EMULATOR_HOST=localhost:8085 mvn -pl nexuslink-protocol-pubsub test -Dnexuslink.it=true -Dtest=PubSubLiveIT</pre>
 * Gated on BOTH {@code -Dnexuslink.it=true} and the env var so it only runs when pointed at an emulator.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@EnabledIfEnvironmentVariable(named = "PUBSUB_EMULATOR_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PubSubLiveIT {

    private static final String PROJECT = "nexus-it";
    private static final String TOPIC = "orders-" + System.nanoTime();
    private static final String SUB = TOPIC + "-sub";

    @Test
    void createPublishPullRoundTrip() throws Exception {
        PubSubService svc = new PubSubService();
        svc.connect(PROJECT);
        assertTrue(svc.isConnected());

        try {
            svc.createTopic(TOPIC);
            assertTrue(svc.listTopics().contains(TOPIC), "topic should be listed after creation");

            svc.createSubscription(SUB, TOPIC, 10);
            assertTrue(svc.listSubscriptions().contains(SUB), "subscription should be listed after creation");

            // Nothing published yet → an immediate pull returns empty.
            assertTrue(svc.pull(SUB, 10).isEmpty(), "no messages before publish");

            String id1 = svc.publish(TOPIC, "hello-pubsub");
            String id2 = svc.publish(TOPIC, "second");
            assertNotNull(id1);
            assertNotEquals(id1, id2);

            // Pull with a short retry loop — the emulator may deliver across a couple of pulls.
            List<String> bodies = new java.util.ArrayList<>();
            for (int attempt = 0; attempt < 10 && bodies.size() < 2; attempt++) {
                for (PubSubService.PulledMessage m : svc.pull(SUB, 10)) bodies.add(m.data());
                if (bodies.size() < 2) Thread.sleep(200);
            }
            assertTrue(bodies.contains("hello-pubsub"), "first message should be pulled and acked");
            assertTrue(bodies.contains("second"), "second message should be pulled and acked");

            // Everything acked → the next pull is empty again.
            assertTrue(svc.pull(SUB, 10).isEmpty(), "no un-acked messages remain");
        } finally {
            try { svc.deleteSubscription(SUB); } catch (Exception ignored) { }
            try { svc.deleteTopic(TOPIC); } catch (Exception ignored) { }
        }
    }
}
