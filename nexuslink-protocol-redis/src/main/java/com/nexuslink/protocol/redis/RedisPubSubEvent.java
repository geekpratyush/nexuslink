package com.nexuslink.protocol.redis;

import java.util.List;
import java.util.Locale;

/**
 * A decoded Redis Pub/Sub push frame: either a delivered {@link RedisMessage} or a
 * {@link RedisSubscriptionEvent} confirmation.
 *
 * <p>Pub/Sub frames arrive as a RESP2 array (leading {@code *}) or a RESP3 push (leading {@code >}),
 * with the first element naming the frame kind:
 * <pre>
 *   subscribe    ["subscribe",   channel, count]
 *   unsubscribe  ["unsubscribe", channel, count]
 *   psubscribe   ["psubscribe",  pattern, count]
 *   punsubscribe ["punsubscribe",pattern, count]
 *   message      ["message",     channel, payload]
 *   pmessage     ["pmessage",    pattern, channel, payload]
 * </pre>
 *
 * <p>{@link #parse(RespValue)} turns one such frame (already decoded by {@link RespCodec}) into the
 * matching event — this is the pure, transport-free heart of the subscriber and is unit-tested
 * directly against raw RESP bytes.
 */
public sealed interface RedisPubSubEvent permits RedisMessage, RedisSubscriptionEvent {

    /**
     * Classifies a decoded RESP push frame as a {@link RedisMessage} or a
     * {@link RedisSubscriptionEvent}.
     *
     * @throws RespException if the frame is not a well-formed Pub/Sub frame
     */
    static RedisPubSubEvent parse(RespValue frame) {
        List<RespValue> items = itemsOf(frame);
        if (items == null || items.isEmpty()) {
            throw new RespException("not a pub/sub frame (empty): " + frame);
        }
        String kind = text(items.get(0));
        if (kind == null) {
            throw new RespException("pub/sub frame is missing its kind: " + frame);
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "message" -> {
                require(items, 3, kind);
                yield new RedisMessage(text(items.get(1)), null, text(items.get(2)));
            }
            case "pmessage" -> {
                require(items, 4, kind);
                yield new RedisMessage(text(items.get(2)), text(items.get(1)), text(items.get(3)));
            }
            case "subscribe", "unsubscribe", "psubscribe", "punsubscribe" -> {
                require(items, 3, kind);
                yield new RedisSubscriptionEvent(
                        RedisSubscriptionEvent.Kind.fromWire(kind),
                        text(items.get(1)),
                        longOf(items.get(2)));
            }
            default -> throw new RespException("unknown pub/sub frame kind: '" + kind + "'");
        };
    }

    private static List<RespValue> itemsOf(RespValue frame) {
        return switch (frame) {
            case RespValue.RespArray a -> a.items();
            case RespValue.RespPush p -> p.items();
            default -> throw new RespException(
                    "a pub/sub frame must be a RESP array or push, got " + frame);
        };
    }

    private static void require(List<RespValue> items, int n, String kind) {
        if (items.size() < n) {
            throw new RespException(
                    "'" + kind + "' frame needs " + n + " elements, got " + items.size());
        }
    }

    private static String text(RespValue v) {
        return switch (v) {
            case RespValue.BulkString b -> b.asText();
            case RespValue.SimpleString s -> s.value();
            case RespValue.VerbatimString vs -> vs.value();
            case RespValue.RespNull ignored -> null;
            case null -> null;
            default -> throw new RespException("expected a string in a pub/sub frame, got " + v);
        };
    }

    private static long longOf(RespValue v) {
        return switch (v) {
            case RespValue.RespInteger i -> i.value();
            case RespValue.BulkString b -> Long.parseLong(b.asText());
            case RespValue.SimpleString s -> Long.parseLong(s.value());
            default -> throw new RespException("expected an integer in a pub/sub frame, got " + v);
        };
    }
}
