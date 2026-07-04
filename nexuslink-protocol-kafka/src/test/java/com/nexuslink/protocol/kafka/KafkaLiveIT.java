package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Kafka produce/consume round-trip against the local {@code test-env} stack.
 * <pre>docker compose -f test-env/docker-compose.yml up -d kafka</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class KafkaLiveIT {

    @Test
    void produceThenConsume() throws Exception {
        String topic = "nexus-it-" + System.currentTimeMillis();
        try (KafkaService svc = new KafkaService()) {
            svc.connect("localhost:9092", Map.of());
            assertTrue(svc.isConnected());

            KafkaService.SendResult r = svc.send(topic, "k1", "hello-nexus");
            assertTrue(r.offset() >= 0);
            assertTrue(svc.listTopics().contains(topic));

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<KafkaService.KafkaMessage> got = new AtomicReference<>();
            svc.startConsuming(topic, "nexus-it-grp", true, new KafkaService.MessageListener() {
                @Override public void onMessage(KafkaService.KafkaMessage m) { got.set(m); latch.countDown(); }
                @Override public void onError(Throwable e) { latch.countDown(); }
            });

            assertTrue(latch.await(20, TimeUnit.SECONDS), "message not received within 20s");
            svc.stopConsuming();
            assertNotNull(got.get());
            assertEquals("k1", got.get().key());
            assertEquals("hello-nexus", got.get().value());
        }
    }

    @Test
    void browseReadsWithoutConsumerGroupSideEffects() throws Exception {
        String topic = "nexus-browse-" + System.currentTimeMillis();
        try (KafkaService svc = new KafkaService()) {
            svc.connect("localhost:9092", Map.of());
            svc.send(topic, "a", "one");
            svc.send(topic, "b", "two");

            List<KafkaService.KafkaMessage> msgs = svc.browse(topic, 10, true);
            assertEquals(2, msgs.size(), "both produced records should be browsed");
            assertEquals("one", msgs.get(0).value());

            // No group.id was used, so no consumer group should have been created by browsing.
            assertFalse(svc.listConsumerGroups().stream().anyMatch(g -> g.startsWith("nexus-browse")),
                    "browse must not create a consumer group");
        }
    }

    @Test
    void createDescribeThenDeleteTopic() throws Exception {
        String topic = "nexus-admin-" + System.currentTimeMillis();
        try (KafkaService svc = new KafkaService()) {
            svc.connect("localhost:9092", Map.of());

            svc.createTopic(topic, 3, (short) 1);
            assertTrue(svc.listTopics().contains(topic));
            assertEquals(3, svc.describe(topic).partitions().size());

            svc.deleteTopic(topic);
            // Deletion is async on the broker; poll briefly until it's gone.
            boolean gone = false;
            for (int i = 0; i < 20 && !gone; i++) {
                if (!svc.listTopics().contains(topic)) { gone = true; break; }
                Thread.sleep(250);
            }
            assertTrue(gone, "topic was not deleted within timeout");
        }
    }
}
