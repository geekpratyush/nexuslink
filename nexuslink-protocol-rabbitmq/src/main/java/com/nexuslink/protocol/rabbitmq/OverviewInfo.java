package com.nexuslink.protocol.rabbitmq;

/**
 * A trimmed view of the RabbitMQ {@code GET /api/overview} response: broker identity and the
 * cluster-wide object/message totals shown on the management home page.
 */
public record OverviewInfo(
        String rabbitmqVersion,
        String erlangVersion,
        String clusterName,
        long queues,
        long exchanges,
        long connections,
        long channels,
        long consumers,
        long messages,
        long messagesReady,
        long messagesUnacknowledged) {
}
