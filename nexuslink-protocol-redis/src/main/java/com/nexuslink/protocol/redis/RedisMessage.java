package com.nexuslink.protocol.redis;

/**
 * A message delivered to a Pub/Sub subscriber.
 *
 * <p>Decoded from a RESP {@code message} frame (a plain channel subscription) or a {@code pmessage}
 * frame (a pattern subscription). For a plain {@code message} the {@link #pattern()} is {@code null}
 * and {@link #channel()} is the exact channel; for a {@code pmessage} the {@link #pattern()} is the
 * glob the subscription used and {@link #channel()} is the concrete channel the message arrived on.
 */
public record RedisMessage(String channel, String pattern, String payload) implements RedisPubSubEvent {

    /** {@code true} when this came from a {@code PSUBSCRIBE} (i.e. {@link #pattern()} is non-null). */
    public boolean isPattern() {
        return pattern != null;
    }
}
