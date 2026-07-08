package com.nexuslink.core.event;

/**
 * Posted on the {@link EventBus} whenever a tracked connection changes state.
 *
 * @param protocol short protocol label (e.g. {@code "REST"}, {@code "Kafka"})
 * @param target   the endpoint the connection points at (host, broker list, url…)
 * @param state    the new state
 * @param atMillis  wall-clock time of the transition ({@code System.currentTimeMillis()})
 */
public record ConnectionEvent(String protocol, String target, ConnState state, long atMillis) {}
