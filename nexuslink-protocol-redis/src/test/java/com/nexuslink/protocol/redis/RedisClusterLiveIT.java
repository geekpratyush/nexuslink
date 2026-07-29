package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live test of the {@code redis-cluster://} path against a cluster-enabled Redis.
 *
 * <p>A single-node cluster is enough to exercise it — the client still speaks the cluster protocol,
 * discovers the slot map and routes by slot:
 * <pre>
 * docker run -d --name nl-redis-cluster -p 7000:7000 redis:7-alpine \
 *   redis-server --port 7000 --cluster-enabled yes --cluster-config-file n.conf
 * docker exec nl-redis-cluster redis-cli -p 7000 cluster addslotsrange 0 16383
 * </pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RedisClusterLiveIT {

    private static final String CLUSTER_URI = "redis-cluster://localhost:7000";

    @Test
    void connectsAsClusterAndRoundTripsKeysAcrossSlots() {
        try (RedisService svc = new RedisService()) {
            svc.connect(CLUSTER_URI);

            assertTrue(svc.isConnected());
            assertEquals(RedisTopology.CLUSTER, svc.topology(), "should report a cluster connection");

            // These three keys hash to different slots, so a cluster-unaware client would MOVED-error.
            svc.execute("SET nexus:cluster:a alpha");
            svc.execute("SET nexus:cluster:b bravo");
            svc.execute("SET nexus:cluster:c charlie");

            assertEquals("alpha", svc.value("nexus:cluster:a"));
            assertEquals("bravo", svc.value("nexus:cluster:b"));
            assertEquals("charlie", svc.value("nexus:cluster:c"));
            assertEquals("string", svc.type("nexus:cluster:a"));

            // SCAN over a cluster must sweep every node, not just the one we seeded from.
            List<String> keys = svc.scanKeys("nexus:cluster:*", 100);
            assertTrue(keys.contains("nexus:cluster:a"), "scan missed a; got " + keys);
            assertTrue(keys.contains("nexus:cluster:b"), "scan missed b; got " + keys);
            assertTrue(keys.contains("nexus:cluster:c"), "scan missed c; got " + keys);

            svc.execute("DEL nexus:cluster:a");
            svc.execute("DEL nexus:cluster:b");
            svc.execute("DEL nexus:cluster:c");
        }
    }

    /** The Pub/Sub panel opens its own raw socket, which must resolve the cluster URI to a seed. */
    @Test
    void pubSubWorksOverAClusterUri() throws Exception {
        java.util.concurrent.BlockingQueue<RedisMessage> got = new java.util.concurrent.LinkedBlockingQueue<>();
        try (RedisSubscriber sub = RedisSubscriber.connect(CLUSTER_URI, got::add);
             RedisService svc = new RedisService()) {
            sub.subscribe("nexus:cluster:chan");
            Thread.sleep(300); // let SUBSCRIBE land before publishing

            svc.connect(CLUSTER_URI);
            svc.publish("nexus:cluster:chan", "ping-over-cluster");

            RedisMessage msg = got.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(msg, "no message delivered over the cluster connection");
            assertEquals("nexus:cluster:chan", msg.channel());
            assertEquals("ping-over-cluster", msg.payload());
        }
    }
}
