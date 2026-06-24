package com.nexuslink.protocol.http.rest;

import java.util.List;
import java.util.Map;

/**
 * Immutable result of a REST execution, including per-phase timing.
 */
public record RestResponse(
        int statusCode,
        String statusText,
        Map<String, List<String>> headers,
        String body,
        long bodyBytes,
        String httpVersion,
        Timing timing,
        boolean failed,
        String errorMessage
) {
    /** Per-phase timing in milliseconds (a simplified waterfall). */
    public record Timing(
            long dnsMs,
            long connectMs,
            long tlsMs,
            long ttfbMs,      // time to first byte
            long downloadMs,
            long totalMs
    ) {}

    public static RestResponse error(String message, long totalMs) {
        return new RestResponse(0, "", Map.of(), "", 0, "",
                new Timing(0, 0, 0, 0, 0, totalMs), true, message);
    }

    /** Coarse status class for colour coding: 2/3/4/5, or 0 on error. */
    public int statusClass() {
        return statusCode / 100;
    }

    public String prettyBytes() {
        if (bodyBytes < 1024) return bodyBytes + " B";
        if (bodyBytes < 1024 * 1024) return String.format("%.1f KB", bodyBytes / 1024.0);
        return String.format("%.1f MB", bodyBytes / (1024.0 * 1024));
    }
}
