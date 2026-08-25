package com.nexuslink.protocol.db;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a JDBC URL back to the catalog entry that speaks it, so a connection opened from a saved
 * profile (or a pasted URL) shows the database it is actually talking to rather than whatever the
 * picker happened to default to.
 */
public final class JdbcUrls {

    private JdbcUrls() {
    }

    /**
     * Subprotocols that don't literally match the sample URL of the driver that handles them —
     * vendor aliases the drivers accept interchangeably.
     */
    private static final Map<String, String> ALIASES = Map.of(
            "clickhouse", "ch",
            "postgres", "postgresql");

    /**
     * The token after {@code jdbc:} — {@code "postgresql"} for
     * {@code jdbc:postgresql://localhost/db}, lowercased. Empty when the text is not a JDBC URL.
     */
    public static Optional<String> subprotocol(String url) {
        if (url == null) return Optional.empty();
        String s = url.trim().toLowerCase(Locale.ROOT);
        if (!s.startsWith("jdbc:")) return Optional.empty();
        String rest = s.substring("jdbc:".length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ':' || c == '/' || c == ';' || c == '@' || c == '?') {
                end = i;
                break;
            }
        }
        String sub = rest.substring(0, end);
        return sub.isEmpty() ? Optional.empty() : Optional.of(sub);
    }

    /**
     * The first driver in {@code drivers} whose sample URL uses the same subprotocol as {@code url}.
     * Catalog order decides ties, so {@code jdbc:postgresql:} resolves to PostgreSQL rather than to
     * CockroachDB, which merely reuses the same wire driver.
     */
    public static Optional<DriverInfo> forUrl(List<DriverInfo> drivers, String url) {
        Optional<String> sub = subprotocol(url);
        if (sub.isEmpty() || drivers == null) return Optional.empty();
        String wanted = ALIASES.getOrDefault(sub.get(), sub.get());
        for (DriverInfo d : drivers) {
            Optional<String> theirs = subprotocol(d.sampleUrl());
            if (theirs.isPresent() && ALIASES.getOrDefault(theirs.get(), theirs.get()).equals(wanted)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }
}
