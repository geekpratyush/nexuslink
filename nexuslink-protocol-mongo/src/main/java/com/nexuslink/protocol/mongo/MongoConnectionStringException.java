package com.nexuslink.protocol.mongo;

/**
 * Thrown when a MongoDB connection string cannot be parsed because it violates the
 * MongoDB URI format (missing scheme, empty host list, or an illegal {@code +srv} shape).
 */
public class MongoConnectionStringException extends IllegalArgumentException {

    public MongoConnectionStringException(String message) {
        super(message);
    }
}
