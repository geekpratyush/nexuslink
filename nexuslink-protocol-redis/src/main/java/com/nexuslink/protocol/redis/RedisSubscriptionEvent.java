package com.nexuslink.protocol.redis;

import java.util.Locale;

/**
 * A subscription-state confirmation pushed by the server in reply to
 * {@code SUBSCRIBE}/{@code UNSUBSCRIBE}/{@code PSUBSCRIBE}/{@code PUNSUBSCRIBE}.
 *
 * <p>{@link #target()} is the channel (for the plain forms) or the pattern (for the {@code p}
 * forms); {@link #subscriptionCount()} is the number of channels/patterns the connection is
 * subscribed to after this change. On an unsubscribe-all the target may be {@code null}.
 */
public record RedisSubscriptionEvent(Kind kind, String target, long subscriptionCount)
        implements RedisPubSubEvent {

    /** The kind of subscription change reported. */
    public enum Kind {
        SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE;

        /** Maps a RESP frame kind token (e.g. {@code "psubscribe"}) to a {@link Kind}. */
        public static Kind fromWire(String token) {
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "subscribe" -> SUBSCRIBE;
                case "unsubscribe" -> UNSUBSCRIBE;
                case "psubscribe" -> PSUBSCRIBE;
                case "punsubscribe" -> PUNSUBSCRIBE;
                default -> throw new RespException("not a subscription event kind: '" + token + "'");
            };
        }

        /** {@code true} for the pattern forms ({@code PSUBSCRIBE}/{@code PUNSUBSCRIBE}). */
        public boolean isPattern() {
            return this == PSUBSCRIBE || this == PUNSUBSCRIBE;
        }
    }
}
