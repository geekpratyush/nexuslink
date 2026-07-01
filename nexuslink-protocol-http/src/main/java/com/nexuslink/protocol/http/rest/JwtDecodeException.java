package com.nexuslink.protocol.http.rest;

/**
 * Signals that a string handed to {@link JwtDecoder} is not a structurally valid JSON Web Token:
 * the wrong number of {@code .}-separated segments, a header/payload segment that is not valid
 * Base64URL, or Base64URL-decoded bytes that are not a JSON object.
 *
 * <p>This is strictly a <em>structural</em> failure. Because {@link JwtDecoder} never inspects or
 * verifies the signature, a token with a wrong, forged, or entirely absent signature does
 * <em>not</em> raise this exception — signature validity is deliberately out of scope for
 * inspection.
 */
public final class JwtDecodeException extends RuntimeException {

    public JwtDecodeException(String message) {
        super(message);
    }

    public JwtDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
