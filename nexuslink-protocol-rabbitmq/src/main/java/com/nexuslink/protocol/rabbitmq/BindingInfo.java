package com.nexuslink.protocol.rabbitmq;

/**
 * A binding as reported by {@code GET /api/bindings} (or {@code /api/bindings/{vhost}}). A binding
 * whose {@code source} is empty is the implicit default-exchange binding to a queue of the same
 * name as the routing key.
 */
public record BindingInfo(
        String source,
        String vhost,
        String destination,
        String destinationType,
        String routingKey) {
}
