package com.nexuslink.protocol.rabbitmq;

/** Unchecked failure from a RabbitMQ management API call (HTTP error, bad JSON, transport error). */
public class RabbitMqManagementException extends RuntimeException {

    public RabbitMqManagementException(String message) {
        super(message);
    }

    public RabbitMqManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
