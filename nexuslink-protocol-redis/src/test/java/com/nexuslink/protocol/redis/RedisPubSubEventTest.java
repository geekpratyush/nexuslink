package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure parsing tests for the Pub/Sub push framing: raw RESP bytes are fed through {@link RespCodec}
 * and classified by {@link RedisPubSubEvent#parse(RespValue)}, with no socket involved.
 */
class RedisPubSubEventTest {

    private final RespCodec codec = new RespCodec();

    private RedisPubSubEvent parse(String wire) {
        return RedisPubSubEvent.parse(codec.decode(wire.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ messages

    @Test
    void decodesPlainMessageFrame() {
        RedisPubSubEvent event = parse("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$5\r\nhello\r\n");
        RedisMessage msg = assertInstanceOf(RedisMessage.class, event);
        assertEquals("news", msg.channel());
        assertNull(msg.pattern());
        assertEquals("hello", msg.payload());
        assertFalse(msg.isPattern());
    }

    @Test
    void decodesPatternMessageFrame() {
        // pmessage(pattern=news.*, channel=news.sports, payload=goal!)
        RedisPubSubEvent event =
                parse("*4\r\n$8\r\npmessage\r\n$6\r\nnews.*\r\n$11\r\nnews.sports\r\n$5\r\ngoal!\r\n");
        RedisMessage msg = assertInstanceOf(RedisMessage.class, event);
        assertEquals("news.*", msg.pattern());
        assertEquals("news.sports", msg.channel());
        assertEquals("goal!", msg.payload());
        assertTrue(msg.isPattern());
    }

    @Test
    void decodesMessageDeliveredAsResp3Push() {
        RedisPubSubEvent event = parse(">3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$5\r\nhello\r\n");
        RedisMessage msg = assertInstanceOf(RedisMessage.class, event);
        assertEquals("news", msg.channel());
        assertEquals("hello", msg.payload());
    }

    @Test
    void messagePayloadMayBeEmpty() {
        RedisMessage msg = (RedisMessage)
                parse("*3\r\n$7\r\nmessage\r\n$4\r\nnews\r\n$0\r\n\r\n");
        assertEquals("", msg.payload());
    }

    // ------------------------------------------------------------------ confirmations

    @Test
    void decodesSubscribeConfirmation() {
        RedisPubSubEvent event = parse("*3\r\n$9\r\nsubscribe\r\n$4\r\nnews\r\n:1\r\n");
        RedisSubscriptionEvent sub = assertInstanceOf(RedisSubscriptionEvent.class, event);
        assertEquals(RedisSubscriptionEvent.Kind.SUBSCRIBE, sub.kind());
        assertEquals("news", sub.target());
        assertEquals(1, sub.subscriptionCount());
        assertFalse(sub.kind().isPattern());
    }

    @Test
    void decodesPsubscribeConfirmation() {
        RedisPubSubEvent event = parse("*3\r\n$10\r\npsubscribe\r\n$6\r\nnews.*\r\n:2\r\n");
        RedisSubscriptionEvent sub = assertInstanceOf(RedisSubscriptionEvent.class, event);
        assertEquals(RedisSubscriptionEvent.Kind.PSUBSCRIBE, sub.kind());
        assertEquals("news.*", sub.target());
        assertEquals(2, sub.subscriptionCount());
        assertTrue(sub.kind().isPattern());
    }

    @Test
    void decodesUnsubscribeConfirmation() {
        RedisPubSubEvent event = parse("*3\r\n$11\r\nunsubscribe\r\n$4\r\nnews\r\n:0\r\n");
        RedisSubscriptionEvent sub = assertInstanceOf(RedisSubscriptionEvent.class, event);
        assertEquals(RedisSubscriptionEvent.Kind.UNSUBSCRIBE, sub.kind());
        assertEquals("news", sub.target());
        assertEquals(0, sub.subscriptionCount());
    }

    @Test
    void decodesUnsubscribeAllWithNullChannel() {
        // UNSUBSCRIBE with no active subscriptions: the channel element is a null bulk string.
        RedisPubSubEvent event = parse("*3\r\n$11\r\nunsubscribe\r\n$-1\r\n:0\r\n");
        RedisSubscriptionEvent sub = assertInstanceOf(RedisSubscriptionEvent.class, event);
        assertNull(sub.target());
        assertEquals(0, sub.subscriptionCount());
    }

    // ------------------------------------------------------------------ errors

    @Test
    void rejectsNonArrayFrame() {
        assertThrows(RespException.class,
                () -> RedisPubSubEvent.parse(new RespValue.SimpleString("PONG")));
    }

    @Test
    void rejectsUnknownKind() {
        assertThrows(RespException.class,
                () -> parse("*2\r\n$4\r\npong\r\n$1\r\nx\r\n"));
    }

    @Test
    void rejectsTruncatedMessageFrame() {
        assertThrows(RespException.class,
                () -> parse("*2\r\n$7\r\nmessage\r\n$4\r\nnews\r\n"));
    }
}
