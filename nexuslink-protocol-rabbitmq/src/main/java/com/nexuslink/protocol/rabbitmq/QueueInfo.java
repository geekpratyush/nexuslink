package com.nexuslink.protocol.rabbitmq;

/**
 * A queue as reported by the RabbitMQ management API ({@code GET /api/queues} or
 * {@code /api/queues/{vhost}/{name}}). Depth counters default to {@code 0} when the broker omits
 * them (e.g. a freshly declared queue with no statistics yet).
 */
public record QueueInfo(
        String name,
        String vhost,
        String type,
        boolean durable,
        boolean autoDelete,
        String state,
        String node,
        long messages,
        long messagesReady,
        long messagesUnacknowledged,
        long consumers) {
}
