package com.nexuslink.protocol.redis;

/**
 * Signals that the input ended before a full RESP reply could be decoded — i.e. the caller supplied
 * a partial/streaming buffer. Callers that read from a socket should keep buffering and retry with
 * more bytes when they catch this.
 */
public final class RespIncompleteException extends RespException {

    public RespIncompleteException(String message) {
        super(message);
    }
}
