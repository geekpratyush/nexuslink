package com.nexuslink.protocol.rabbitmq;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure builder for the {@code x-*} arguments passed to a {@code queue.declare} so that messages
 * dead-letter to another exchange. Produces the argument map AMQP clients expect:
 *
 * <ul>
 *   <li>{@code x-dead-letter-exchange} &mdash; exchange that rejected/expired/over-length messages
 *       are republished to;</li>
 *   <li>{@code x-dead-letter-routing-key} &mdash; routing key to use instead of the message's
 *       original one (optional);</li>
 *   <li>{@code x-message-ttl} &mdash; per-queue message time-to-live, in milliseconds, after which
 *       a message expires and is dead-lettered (optional);</li>
 *   <li>{@code x-max-length} &mdash; maximum ready-message count before the overflow policy kicks
 *       in (optional);</li>
 *   <li>{@code x-overflow} &mdash; overflow behaviour, e.g. {@code reject-publish} or
 *       {@code drop-head} (optional).</li>
 * </ul>
 *
 * <p>The builder is immutable-friendly: each setter returns {@code this}, and {@link #build()}
 * returns a fresh, insertion-ordered map that the caller owns.
 */
public final class DeadLetterArgs {

    /** Argument-map key for the dead-letter target exchange. */
    public static final String DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    /** Argument-map key for the dead-letter routing key override. */
    public static final String DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";
    /** Argument-map key for the per-queue message TTL (milliseconds). */
    public static final String MESSAGE_TTL = "x-message-ttl";
    /** Argument-map key for the maximum queue length (ready messages). */
    public static final String MAX_LENGTH = "x-max-length";
    /** Argument-map key for the overflow behaviour. */
    public static final String OVERFLOW = "x-overflow";

    private String deadLetterExchange;
    private String deadLetterRoutingKey;
    private Long messageTtlMillis;
    private Long maxLength;
    private String overflow;

    public static DeadLetterArgs builder() {
        return new DeadLetterArgs();
    }

    /** Required: the exchange dead-lettered messages are republished to. */
    public DeadLetterArgs deadLetterExchange(String exchange) {
        this.deadLetterExchange = exchange;
        return this;
    }

    /** Optional routing key to use when dead-lettering instead of the message's original key. */
    public DeadLetterArgs deadLetterRoutingKey(String routingKey) {
        this.deadLetterRoutingKey = routingKey;
        return this;
    }

    /** Optional per-queue message TTL in milliseconds; must be {@code >= 0}. */
    public DeadLetterArgs messageTtl(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("x-message-ttl must be >= 0, was " + millis);
        }
        this.messageTtlMillis = millis;
        return this;
    }

    /** Optional maximum number of ready messages before the overflow policy applies; {@code >= 0}. */
    public DeadLetterArgs maxLength(long maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("x-max-length must be >= 0, was " + maxLength);
        }
        this.maxLength = maxLength;
        return this;
    }

    /** Optional overflow behaviour, e.g. {@code drop-head}, {@code reject-publish}. */
    public DeadLetterArgs overflow(String overflow) {
        this.overflow = overflow;
        return this;
    }

    /**
     * Builds the queue-declaration argument map. Only the fields that were set are emitted, in a
     * stable order. Requires a dead-letter exchange to have been configured.
     */
    public Map<String, Object> build() {
        if (deadLetterExchange == null || deadLetterExchange.isBlank()) {
            throw new IllegalStateException("deadLetterExchange is required");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(DEAD_LETTER_EXCHANGE, deadLetterExchange);
        if (deadLetterRoutingKey != null && !deadLetterRoutingKey.isEmpty()) {
            args.put(DEAD_LETTER_ROUTING_KEY, deadLetterRoutingKey);
        }
        if (messageTtlMillis != null) {
            args.put(MESSAGE_TTL, messageTtlMillis);
        }
        if (maxLength != null) {
            args.put(MAX_LENGTH, maxLength);
        }
        if (overflow != null && !overflow.isBlank()) {
            args.put(OVERFLOW, overflow);
        }
        return args;
    }
}
