package com.nexuslink.protocol.snmp;

/**
 * Thrown when a textual object identifier cannot be parsed into an {@link Oid} — for example an
 * empty string, a component that is not a non-negative decimal integer, or an empty component from
 * a doubled dot ({@code 1..2}). This is an unchecked exception because a malformed OID literal is a
 * programming/configuration error rather than a recoverable runtime condition.
 */
public final class OidFormatException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The offending text that failed to parse, for diagnostics; may be {@code null}. */
    private final String input;

    public OidFormatException(String message, String input) {
        super(message);
        this.input = input;
    }

    /** The raw text that could not be parsed, or {@code null} if it was not captured. */
    public String input() {
        return input;
    }
}
