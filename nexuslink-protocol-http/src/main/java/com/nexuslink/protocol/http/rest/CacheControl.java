package com.nexuslink.protocol.http.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Pure, dependency-free parser and model for an HTTP {@code Cache-Control} header as defined by
 * <a href="https://www.rfc-editor.org/rfc/rfc7234#section-5.2">RFC 7234 &sect;5.2</a>.
 *
 * <p>A {@code Cache-Control} header carries a comma-separated list of cache directives. Each
 * directive is a token, optionally followed by {@code =} and an argument that is either a token
 * or a quoted string:
 *
 * <pre>{@code
 *   Cache-Control: public, max-age=31536000, immutable
 *   Cache-Control: no-cache="Set-Cookie", private
 *   Cache-Control: max-age=30, s-maxage=60
 * }</pre>
 *
 * <p>Directives fall into three groups:
 * <ul>
 *   <li><strong>Boolean</strong> directives that stand alone: {@code no-cache}, {@code no-store},
 *       {@code no-transform}, {@code only-if-cached}, {@code must-revalidate}, {@code public},
 *       {@code private}, {@code proxy-revalidate}, {@code immutable}, {@code must-understand}.</li>
 *   <li><strong>Value</strong> directives carrying a {@code delta-seconds} argument: {@code max-age},
 *       {@code s-maxage}, {@code max-stale}, {@code min-fresh}, {@code stale-while-revalidate},
 *       {@code stale-if-error}. Per RFC 7234 &sect;5.2.1.2 {@code max-stale} may appear with no
 *       value, meaning "any" freshness overrun is acceptable.</li>
 *   <li>Some directives ({@code no-cache}, {@code private}) may instead carry a
 *       <strong>quoted field list</strong> naming header fields, e.g. {@code no-cache="Set-Cookie"}.
 *       The unquoted argument is captured verbatim and reachable via {@link #directive(String)}.</li>
 * </ul>
 *
 * <p>Directive names are case-insensitive and stored lower-cased. Every directive — recognised or
 * not — is preserved in a generic name&rarr;argument map, where the argument is {@code null} for a
 * directive that appeared without an {@code =} value. Unknown directives are therefore kept rather
 * than rejected, which lets callers inspect vendor extensions.
 *
 * <p>Parsing is <strong>lenient</strong>: a malformed comma-separated element (an empty name, or a
 * value directive whose argument is not a valid {@code delta-seconds}) is tolerated — the raw text
 * is still stored, and typed numeric accessors simply report the value as absent rather than
 * throwing. Quoted arguments may themselves contain commas and semicolons without being mistaken
 * for separators. When a directive repeats, the first occurrence wins. Passing {@code null} or a
 * blank string yields an empty result in which every directive is absent.
 *
 * <p>The class performs no network or file I/O and is fully offline-testable. Instances are
 * immutable.
 */
public final class CacheControl {

    private final Map<String, String> directives;

    private CacheControl(Map<String, String> directives) {
        this.directives = directives;
    }

    /**
     * Parses a {@code Cache-Control} header value into its directives.
     *
     * <p>Multiple {@code Cache-Control} header lines from a message may be joined with commas and
     * passed as a single string. Parsing is lenient (see the class documentation).
     *
     * @param input the raw {@code Cache-Control} header value; may be {@code null} or blank
     * @return an immutable {@code CacheControl} holding the parsed directives (possibly empty)
     */
    public static CacheControl parse(String input) {
        Map<String, String> map = new LinkedHashMap<>();
        if (input == null) {
            return new CacheControl(Collections.unmodifiableMap(map));
        }
        int i = 0;
        int n = input.length();
        while (i < n) {
            // Skip leading whitespace and empty comma-separated slots.
            while (i < n && (isWs(input.charAt(i)) || input.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            // Read the directive name up to '=', ',' or whitespace.
            int nameStart = i;
            while (i < n) {
                char c = input.charAt(i);
                if (c == '=' || c == ',' || isWs(c)) {
                    break;
                }
                i++;
            }
            String name = input.substring(nameStart, i).toLowerCase(Locale.ROOT);
            // Allow whitespace between the name and a following '='.
            while (i < n && isWs(input.charAt(i))) {
                i++;
            }
            String value = null;
            if (i < n && input.charAt(i) == '=') {
                i++; // consume '='
                while (i < n && isWs(input.charAt(i))) {
                    i++;
                }
                if (i < n && input.charAt(i) == '"') {
                    // Quoted argument: decode backslash escapes; commas inside are literal.
                    StringBuilder sb = new StringBuilder();
                    i++; // consume opening quote
                    while (i < n) {
                        char qc = input.charAt(i);
                        if (qc == '\\' && i + 1 < n) {
                            sb.append(input.charAt(i + 1));
                            i += 2;
                        } else if (qc == '"') {
                            i++; // consume closing quote
                            break;
                        } else {
                            sb.append(qc);
                            i++;
                        }
                    }
                    value = sb.toString();
                } else {
                    int valStart = i;
                    while (i < n) {
                        char vc = input.charAt(i);
                        if (vc == ',' || isWs(vc)) {
                            break;
                        }
                        i++;
                    }
                    value = input.substring(valStart, i);
                }
            }
            // Discard any trailing junk before the next comma (lenient recovery).
            while (i < n && input.charAt(i) != ',') {
                i++;
            }
            if (!name.isEmpty()) {
                // First occurrence of a repeated directive wins.
                map.putIfAbsent(name, value);
            }
        }
        return new CacheControl(Collections.unmodifiableMap(map));
    }

    /**
     * All parsed directives, keyed by lower-cased name, in first-seen order. A {@code null} value
     * marks a directive that appeared without an {@code =} argument. Never {@code null}, possibly
     * empty; unmodifiable.
     */
    public Map<String, String> directives() {
        return directives;
    }

    /**
     * The raw argument of the named directive, matched case-insensitively.
     *
     * @param name directive name (e.g. {@code "max-age"}); may be {@code null}
     * @return the argument text, or {@code null} if the directive is absent <em>or</em> present
     *         without a value — use {@link #has(String)} to distinguish the two cases
     */
    public String directive(String name) {
        return name == null ? null : directives.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the named directive is present (case-insensitive), regardless of whether it carried
     * a value.
     *
     * @param name directive name; may be {@code null}
     * @return {@code true} if the directive appeared in the header
     */
    public boolean has(String name) {
        return name != null && directives.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Whether no directives were parsed. */
    public boolean isEmpty() {
        return directives.isEmpty();
    }

    // --- Boolean directive accessors ---------------------------------------------------------

    /** Whether the {@code no-store} directive is present (RFC 7234 &sect;5.2.1.5 / &sect;5.2.2.3). */
    public boolean noStore() {
        return has("no-store");
    }

    /** Whether the {@code no-cache} directive is present (RFC 7234 &sect;5.2.1.4 / &sect;5.2.2.2). */
    public boolean noCache() {
        return has("no-cache");
    }

    /** Whether the {@code no-transform} directive is present (RFC 7234 &sect;5.2.1.6 / &sect;5.2.2.4). */
    public boolean noTransform() {
        return has("no-transform");
    }

    /** Whether the {@code only-if-cached} directive is present (RFC 7234 &sect;5.2.1.7). */
    public boolean onlyIfCached() {
        return has("only-if-cached");
    }

    /** Whether the {@code must-revalidate} directive is present (RFC 7234 &sect;5.2.2.1). */
    public boolean mustRevalidate() {
        return has("must-revalidate");
    }

    /** Whether the {@code public} response directive is present (RFC 7234 &sect;5.2.2.5). */
    public boolean isPublic() {
        return has("public");
    }

    /** Whether the {@code private} response directive is present (RFC 7234 &sect;5.2.2.6). */
    public boolean isPrivate() {
        return has("private");
    }

    /** Whether the {@code proxy-revalidate} directive is present (RFC 7234 &sect;5.2.2.7). */
    public boolean proxyRevalidate() {
        return has("proxy-revalidate");
    }

    /** Whether the {@code immutable} directive is present (RFC 8246). */
    public boolean immutable() {
        return has("immutable");
    }

    /** Whether the {@code must-understand} directive is present (RFC 9111 &sect;5.2.2.3). */
    public boolean mustUnderstand() {
        return has("must-understand");
    }

    // --- Value (delta-seconds) directive accessors -------------------------------------------

    /** The {@code max-age} directive as {@code delta-seconds} (RFC 7234 &sect;5.2.1.1 / &sect;5.2.2.8). */
    public OptionalLong maxAge() {
        return deltaSeconds("max-age");
    }

    /** The {@code s-maxage} directive as {@code delta-seconds} (RFC 7234 &sect;5.2.2.9). */
    public OptionalLong sMaxAge() {
        return deltaSeconds("s-maxage");
    }

    /**
     * The {@code max-stale} directive as {@code delta-seconds} (RFC 7234 &sect;5.2.1.2). When
     * {@code max-stale} is present without a value ("any" acceptable staleness), this returns an
     * empty {@link OptionalLong}; distinguish that case from an absent directive with
     * {@link #has(String) has("max-stale")}.
     */
    public OptionalLong maxStale() {
        return deltaSeconds("max-stale");
    }

    /** The {@code min-fresh} directive as {@code delta-seconds} (RFC 7234 &sect;5.2.1.3). */
    public OptionalLong minFresh() {
        return deltaSeconds("min-fresh");
    }

    /** The {@code stale-while-revalidate} directive as {@code delta-seconds} (RFC 5861 &sect;3). */
    public OptionalLong staleWhileRevalidate() {
        return deltaSeconds("stale-while-revalidate");
    }

    /** The {@code stale-if-error} directive as {@code delta-seconds} (RFC 5861 &sect;4). */
    public OptionalLong staleIfError() {
        return deltaSeconds("stale-if-error");
    }

    /**
     * Reads the named directive's argument as a non-negative {@code delta-seconds} value. Returns
     * an empty {@link OptionalLong} when the directive is absent, has no value, or its value is not
     * a valid non-negative integer (lenient handling of malformed input).
     */
    private OptionalLong deltaSeconds(String name) {
        String v = directives.get(name);
        if (v == null || v.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            long parsed = Long.parseLong(v.trim());
            return parsed < 0 ? OptionalLong.empty() : OptionalLong.of(parsed);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Re-renders the directives as a {@code Cache-Control} header value. Directives are emitted in
     * first-seen order, comma-separated. A directive with no value is rendered bare; a value that
     * contains whitespace, a comma or a quote is emitted as a quoted string (with embedded quotes
     * and backslashes escaped), otherwise it is emitted as a bare token. The result round-trips
     * back through {@link #parse(String)}.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : directives.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey());
            String v = e.getValue();
            if (v != null) {
                sb.append('=');
                if (needsQuoting(v)) {
                    sb.append('"');
                    for (int i = 0; i < v.length(); i++) {
                        char c = v.charAt(i);
                        if (c == '"' || c == '\\') {
                            sb.append('\\');
                        }
                        sb.append(c);
                    }
                    sb.append('"');
                } else {
                    sb.append(v);
                }
            }
        }
        return sb.toString();
    }

    private static boolean needsQuoting(String v) {
        if (v.isEmpty()) {
            return true;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ',' || c == '"' || c == '\\' || isWs(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t';
    }
}
