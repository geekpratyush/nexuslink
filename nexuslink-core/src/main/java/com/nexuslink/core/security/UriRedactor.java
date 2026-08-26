package com.nexuslink.core.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides the credentials inside a connection string, and shortens it to something that fits on one
 * line, for the places a connection is <em>displayed</em> rather than edited.
 *
 * <p>Connection strings routinely carry a password in plain sight —
 * {@code mongodb://app:s3cret@host/db}, {@code redis://:pw@host}, {@code amqp://user:pw@broker},
 * {@code jdbc:postgresql://host/db?password=pw}. A toolbar that shows one in full puts that password
 * on every screenshot, screen share and shoulder-surf, which is a strange thing for a tool that has a
 * secret vault. Redacting it costs nothing: the value is still there to edit, it is simply not on
 * display until asked for.
 *
 * <p>Pure and dependency-free, so every scheme's masking is unit-testable.
 */
public final class UriRedactor {

    /** What a hidden secret is replaced with. */
    public static final String MASK = "••••";

    /** {@code scheme://user:password@} — the userinfo form used by nearly every wire protocol. */
    private static final Pattern USERINFO = Pattern.compile(
            "(?<scheme>[a-zA-Z][a-zA-Z0-9+.\\-]*://)(?<user>[^/@:\\s]*)(?::(?<password>[^@\\s]*))?@");

    /** {@code password=...} / {@code pwd=...} in a query string or JDBC property list. */
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)([?&;])((?:password|passwd|pwd|secret|token|apikey|api_key|accesskey|access_key"
                    + "|sessiontoken|session_token|privatekey|private_key)\\s*=)([^&;\\s]*)");

    /** Query parameters worth keeping when a URI is shortened — they change what it points at. */
    private static final List<String> INTERESTING_PARAMS =
            List.of("replicaset", "authsource", "database", "db", "currentschema", "servicename", "sid");

    private UriRedactor() {}

    /**
     * The connection string with any embedded credentials masked. Everything else — scheme, hosts,
     * path, options — is left exactly as it was, because that is the part that tells you which server
     * you are looking at.
     */
    public static String redact(String uri) {
        if (uri == null || uri.isBlank()) return uri == null ? "" : uri;
        String out = maskUserInfo(uri);
        return maskQuerySecrets(out);
    }

    private static String maskUserInfo(String uri) {
        Matcher matcher = USERINFO.matcher(uri);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String password = matcher.group("password");
            String replacement = matcher.group("scheme") + matcher.group("user")
                    + (password == null ? "" : ":" + MASK) + "@";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String maskQuerySecrets(String uri) {
        Matcher matcher = QUERY_SECRET.matcher(uri);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + MASK));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** {@code true} when the string carries a credential that {@link #redact} would hide. */
    public static boolean carriesCredentials(String uri) {
        if (uri == null || uri.isBlank()) return false;
        return !redact(uri).equals(uri);
    }

    /**
     * A short label for a connection: the host (and database, where the URI names one), with the
     * credentials gone — {@code db.internal:5432/orders} rather than the whole string.
     *
     * <p>Falls back to the redacted string when the shape is not recognised, because a slightly long
     * label is better than a wrong one.
     */
    public static String shortLabel(String uri) {
        if (uri == null || uri.isBlank()) return "";
        String redacted = redact(uri.trim());

        // JDBC URLs are jdbc:<engine>:<the rest>; the engine is worth keeping, the prefix is not.
        String working = redacted;
        String prefix = "";
        if (working.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
            int second = working.indexOf(':', 5);
            if (second > 0) {
                prefix = working.substring(5, second) + " ";
                working = working.substring(second + 1);
            }
        }

        int schemeEnd = working.indexOf("://");
        String body = schemeEnd >= 0 ? working.substring(schemeEnd + 3) : working;
        int at = body.lastIndexOf('@');
        if (at >= 0) body = body.substring(at + 1);           // drop user:mask@

        String query = "";
        int question = body.indexOf('?');
        if (question >= 0) {
            query = keepInterestingParams(body.substring(question + 1));
            body = body.substring(0, question);
        }
        if (body.isBlank()) return redacted;
        return (prefix + body + query).trim();
    }

    /** Keeps only the query parameters that change which server or database is meant. */
    private static String keepInterestingParams(String query) {
        StringBuilder kept = new StringBuilder();
        for (String part : query.split("[&;]")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String name = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (INTERESTING_PARAMS.contains(name)) {
                kept.append(kept.length() == 0 ? "?" : "&").append(part);
            }
        }
        return kept.toString();
    }

    /**
     * A display name for a connection: the caller's own name when it has one, otherwise
     * {@link #shortLabel}. This is what a collapsed connection chip shows.
     */
    public static String displayName(String name, String uri) {
        if (name != null && !name.isBlank()) return name.trim();
        String label = shortLabel(uri);
        return label.isBlank() ? "Not connected" : label;
    }
}
