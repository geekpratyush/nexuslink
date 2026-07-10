package com.nexuslink.protocol.solace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live JCSMP round-trip against the local {@code test-env} Solace PubSub+ broker.
 * <pre>docker compose -f test-env/docker-compose.yml up -d solace</pre>
 * Run with {@code -Dnexuslink.it=true}. SMF on localhost:55555, VPN "default", admin / nexus123.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class SolaceJcsmpServiceLiveIT {

    private static final String HOST = "tcp://localhost:55555";

    private static SolaceConnectionProfile profile() {
        return SolaceConnectionProfile.single(HOST, "default", "admin", "nexus123");
    }

    @Test
    void connectsToTheBroker() throws Exception {
        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            assertTrue(solace.isConnected());
        }
    }

    @Test
    void directPublishReachesATopicSubscriber() throws Exception {
        String topic = "nexus/it/direct/" + System.currentTimeMillis();
        CopyOnWriteArrayList<SolaceJcsmpService.SolaceMessage> received = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            solace.subscribe(topic, message -> { received.add(message); latch.countDown(); });

            solace.publishDirect(topic, "hello-direct");

            assertTrue(latch.await(5, TimeUnit.SECONDS), "the subscriber should have received the message");
            assertEquals("hello-direct", received.get(0).body());
            assertEquals(topic, received.get(0).destination());
            assertEquals("DIRECT", received.get(0).deliveryMode());
        }
    }

    @Test
    void directSubscriptionRespectsWildcards() throws Exception {
        String base = "nexus/it/wild/" + System.currentTimeMillis();
        CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);

        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            solace.subscribe(base + "/>", message -> { bodies.add(message.body()); latch.countDown(); });

            solace.publishDirect(base + "/a", "one");
            solace.publishDirect(base + "/b/c", "two");

            assertTrue(latch.await(5, TimeUnit.SECONDS), "the > wildcard should match both levels");
            assertTrue(bodies.contains("one") && bodies.contains("two"));
        }
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        String topic = "nexus/it/unsub/" + System.currentTimeMillis();
        CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();

        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            solace.subscribe(topic, message -> bodies.add(message.body()));
            solace.unsubscribe(topic);

            solace.publishDirect(topic, "after-unsub");
            Thread.sleep(1000); // allow any (incorrect) delivery to arrive

            assertTrue(bodies.isEmpty(), "no message should arrive after unsubscribe");
        }
    }

    @Test
    void guaranteedPublishSpoolsToAQueueAndBrowseSeesItWithoutConsuming() throws Exception {
        String queue = "nexus.it.q." + System.currentTimeMillis();

        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            solace.provisionQueue(queue);

            solace.publishGuaranteed(queue, "spooled-one");
            solace.publishGuaranteed(queue, "spooled-two");

            List<SolaceJcsmpService.SolaceMessage> peeked = solace.browseQueue(queue, 10, 2000);
            assertEquals(2, peeked.size());
            assertEquals("spooled-one", peeked.get(0).body());
            assertEquals("spooled-two", peeked.get(1).body());
            assertEquals("PERSISTENT", peeked.get(0).deliveryMode());

            // Browsing must not remove them — a second browse sees the same two.
            assertEquals(2, solace.browseQueue(queue, 10, 2000).size());
        }
    }

    @Test
    void provisionQueueIsIdempotent() throws Exception {
        String queue = "nexus.it.idem." + System.currentTimeMillis();
        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            solace.provisionQueue(queue);
            assertDoesNotThrow(() -> solace.provisionQueue(queue), "re-provisioning an existing queue must be a no-op");
        }
    }

    @Test
    void browseOnAnEmptyQueueReturnsEmpty() throws Exception {
        String queue = "nexus.it.empty." + System.currentTimeMillis();
        try (SolaceJcsmpService solace = new SolaceJcsmpService()) {
            solace.connect(profile());
            solace.provisionQueue(queue);

            assertTrue(solace.browseQueue(queue, 10, 1000).isEmpty());
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        SolaceJcsmpService solace = new SolaceJcsmpService();
        solace.connect(profile());
        assertTrue(solace.isConnected());

        solace.close();
        assertFalse(solace.isConnected());
        solace.close(); // must not throw
    }
}
