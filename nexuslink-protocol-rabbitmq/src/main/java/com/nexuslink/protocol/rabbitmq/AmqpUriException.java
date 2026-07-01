package com.nexuslink.protocol.rabbitmq;

/**
 * Unchecked failure raised when an AMQP connection URI cannot be parsed &mdash; a bad scheme, a
 * malformed port, an illegal percent escape or an unexpected extra path segment.
 */
public class AmqpUriException extends RuntimeException {

    public AmqpUriException(String message) {
        super(message);
    }

    public AmqpUriException(String message, Throwable cause) {
        super(message, cause);
    }
}
