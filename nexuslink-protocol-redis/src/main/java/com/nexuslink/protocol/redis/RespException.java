package com.nexuslink.protocol.redis;

/** Thrown when a RESP payload is malformed or cannot be encoded/decoded. */
public class RespException extends RuntimeException {

    public RespException(String message) {
        super(message);
    }

    public RespException(String message, Throwable cause) {
        super(message, cause);
    }
}
