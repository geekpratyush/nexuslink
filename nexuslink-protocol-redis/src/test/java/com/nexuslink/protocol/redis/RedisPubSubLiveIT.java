package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Pub/Sub round-trip against the local {@code test-env} Redis: subscribe on one connection,
 * {@code PUBLISH} from another, and assert the message is delivered.
 * <pre>docker compose -f test-env/docker-compose.yml up -d redis</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RedisPubSubLiveIT {

    private static final String URI = "redis://localhost:6379";

    /** PUBLISHes a message over a throwaway raw-socket connection and returns the subscriber count. */
    private long publish(String channel, String payload) throws Exception {
        RespCodec codec = new RespCodec();
        try (Socket pub = new Socket("localhost", 6379)) {
            pub.getOutputStream().write(codec.encodeCommand("PUBLISH", channel, payload));
            pub.getOutputStream().flush();
            RespValue reply = codec.decode(pub.getInputStream());
            return assertInstanceOf(RespValue.RespInteger.class, reply).value();
        }
    }

    @Test
    void subscribeReceivesPublishedMessage() throws Exception {
        String channel = "nexus:it:pubsub:" + System.nanoTime();
        BlockingQueue<RedisMessage> received = new LinkedBlockingQueue<>();
        CountDownLatch subscribed = new CountDownLatch(1);

        try (RedisSubscriber sub = RedisSubscriber.connect(URI, received::add,
                ev -> { if (ev.kind() == RedisSubscriptionEvent.Kind.SUBSCRIBE) subscribed.countDown(); },
                null)) {
            sub.subscribe(channel);
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "no subscribe confirmation");

            assertEquals(1, publish(channel, "hello-pubsub"), "publisher saw no subscriber");

            RedisMessage msg = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "no message delivered");
            assertEquals(channel, msg.channel());
            assertNull(msg.pattern());
            assertEquals("hello-pubsub", msg.payload());
            assertTrue(sub.channels().contains(channel));
        }
    }

    @Test
    void psubscribeReceivesPatternMessage() throws Exception {
        String prefix = "nexus:it:pat:" + System.nanoTime();
        String pattern = prefix + ".*";
        String channel = prefix + ".sports";
        BlockingQueue<RedisMessage> received = new LinkedBlockingQueue<>();
        CountDownLatch subscribed = new CountDownLatch(1);

        try (RedisSubscriber sub = RedisSubscriber.connect(URI, received::add,
                ev -> { if (ev.kind() == RedisSubscriptionEvent.Kind.PSUBSCRIBE) subscribed.countDown(); },
                null)) {
            sub.psubscribe(pattern);
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "no psubscribe confirmation");

            assertEquals(1, publish(channel, "goal!"), "publisher saw no subscriber");

            RedisMessage msg = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(msg, "no pattern message delivered");
            assertEquals(pattern, msg.pattern());
            assertEquals(channel, msg.channel());
            assertEquals("goal!", msg.payload());
            assertTrue(msg.isPattern());
        }
    }
}
