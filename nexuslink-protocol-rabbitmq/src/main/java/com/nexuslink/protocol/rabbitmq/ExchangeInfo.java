package com.nexuslink.protocol.rabbitmq;

/**
 * An exchange as reported by {@code GET /api/exchanges} (or {@code /api/exchanges/{vhost}}). The
 * default (nameless) exchange is surfaced with an empty {@code name}.
 */
public record ExchangeInfo(
        String name,
        String vhost,
        String type,
        boolean durable,
        boolean autoDelete,
        boolean internal) {
}
