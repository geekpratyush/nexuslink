package com.nexuslink.ui.hint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure mapping from an error message to the best Help navigation target
 * ({@code troubleshooting#anchor}). Kept free of JavaFX so it can be unit-tested and reused by
 * {@link ErrorHelpLink}.
 * <p>
 * Anchors are slugified exactly the way {@code HelpDialog} builds them from headings
 * ({@code lowercase → non-alphanumeric runs to '-'}), so every target resolves to a real anchor in
 * {@code troubleshooting.md}.
 */
public final class ErrorHelp {

    private ErrorHelp() {}

    private static final String TOPIC = "troubleshooting";

    /** Ordered signature → heading text. First substring match wins (case-insensitive). */
    private static final Map<String, String> SIGNATURES = new LinkedHashMap<>();
    static {
        SIGNATURES.put("connection refused", "Connection refused");
        SIGNATURES.put("connect timed out",  "Timeout");
        SIGNATURES.put("read timed out",     "Timeout");
        SIGNATURES.put("timeout",            "Timeout");
        SIGNATURES.put("timed out",          "Timeout");
        SIGNATURES.put("handshake",          "SSL / TLS handshake failed");
        SIGNATURES.put("pkix",               "SSL / TLS handshake failed");
        SIGNATURES.put("certificate",        "SSL / TLS handshake failed");
        SIGNATURES.put("sslhandshake",       "SSL / TLS handshake failed");
        SIGNATURES.put("ssl",                "SSL / TLS handshake failed");
        SIGNATURES.put("tls",                "SSL / TLS handshake failed");
        SIGNATURES.put("401",                "401 / 403 (auth failed)");
        SIGNATURES.put("403",                "401 / 403 (auth failed)");
        SIGNATURES.put("unauthorized",       "401 / 403 (auth failed)");
        SIGNATURES.put("forbidden",          "401 / 403 (auth failed)");
        SIGNATURES.put("authentication",     "401 / 403 (auth failed)");
        SIGNATURES.put("vault locked",       "Vault locked");
        SIGNATURES.put("vault is locked",    "Vault locked");
    }

    /**
     * Returns the {@code troubleshooting#anchor} target most relevant to {@code errorText}, or
     * {@code null} if no signature matches (caller should then hide the help link).
     */
    public static String targetFor(String errorText) {
        if (errorText == null || errorText.isBlank()) return null;
        String haystack = errorText.toLowerCase();
        for (Map.Entry<String, String> e : SIGNATURES.entrySet()) {
            if (haystack.contains(e.getKey())) {
                return TOPIC + "#" + slug(e.getValue());
            }
        }
        return null;
    }

    /** True if a specific help anchor exists for this error. */
    public static boolean hasHelp(String errorText) {
        return targetFor(errorText) != null;
    }

    /** Slugify a heading exactly the way the Help renderer does, so anchors always resolve. */
    static String slug(String heading) {
        return heading.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
