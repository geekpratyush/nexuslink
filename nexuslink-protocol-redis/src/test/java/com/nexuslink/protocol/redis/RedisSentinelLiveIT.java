package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live test of the {@code redis-sentinel://} path — a sentinel monitoring the {@code test-env} Redis.
 *
 * <pre>
 * docker compose -f test-env/docker-compose.yml up -d redis
 * printf 'port 26379\nsentinel monitor mymaster 127.0.0.1 6379 1\n' &gt; sentinel.conf
 * docker run -d --name nl-redis-sentinel --network host \
 *   -v $PWD/sentinel.conf:/etc/sentinel.conf redis:7-alpine redis-sentinel /etc/sentinel.conf
 * </pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RedisSentinelLiveIT {

    private static final String SENTINEL_URI = "redis-sentinel://localhost:26379#mymaster";

    @Test
    void connectsThroughSentinelAndRoundTripsAKey() {
        try (RedisService svc = new RedisService()) {
            svc.connect(SENTINEL_URI);

            assertTrue(svc.isConnected());
            assertEquals(RedisTopology.SENTINEL, svc.topology());

            svc.execute("SET nexus:sentinel:k sentinel-value");
            assertEquals("sentinel-value", svc.value("nexus:sentinel:k"));
            svc.execute("DEL nexus:sentinel:k");
        }
    }

    /**
     * The raw-socket subscriber can't speak the sentinel protocol, so it asks the sentinel for the
     * current master address and connects there — this proves that resolution against a real sentinel.
     */
    @Test
    void subscriberResolvesTheMasterFromARealSentinel() {
        String resolved = RedisSubscriber.resolve(SENTINEL_URI);
        assertEquals("redis://127.0.0.1:6379", resolved);
    }

    @Test
    void pubSubWorksOverASentinelUri() throws Exception {
        java.util.concurrent.BlockingQueue<RedisMessage> got = new java.util.concurrent.LinkedBlockingQueue<>();
        try (RedisSubscriber sub = RedisSubscriber.connect(SENTINEL_URI, got::add);
             RedisService svc = new RedisService()) {
            sub.subscribe("nexus:sentinel:chan");
            Thread.sleep(300);

            svc.connect(SENTINEL_URI);
            svc.publish("nexus:sentinel:chan", "ping-via-sentinel");

            RedisMessage msg = got.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(msg, "no message delivered over the sentinel-resolved connection");
            assertEquals("ping-via-sentinel", msg.payload());
        }
    }

    /** An unknown master name must fail with a clear message, not a socket error. */
    @Test
    void unknownMasterNameIsReportedClearly() {
        RespException e = assertThrows(RespException.class,
                () -> RedisSubscriber.resolve("redis-sentinel://localhost:26379#no-such-master"));
        assertTrue(e.getMessage().contains("no-such-master"), "unhelpful message: " + e.getMessage());
    }
}
