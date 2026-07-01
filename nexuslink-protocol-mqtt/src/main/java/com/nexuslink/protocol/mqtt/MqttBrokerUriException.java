package com.nexuslink.protocol.mqtt;

/**
 * Thrown by {@link MqttBrokerUri#parse(String)} when a broker connection URI is malformed — an
 * unknown scheme, a missing host, an out-of-range or non-numeric port, or bad percent-encoding.
 * Unchecked so callers can validate input at the edge without wrapping.
 */
public class MqttBrokerUriException extends IllegalArgumentException {

    public MqttBrokerUriException(String message) {
        super(message);
    }
}
