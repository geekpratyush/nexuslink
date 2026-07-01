package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, dependency-free parser and model for an HTTP {@code Link} header as defined by
 * <a href="https://www.rfc-editor.org/rfc/rfc8288">RFC 8288 (Web Linking)</a>.
 *
 * <p>A {@code Link} header carries one or more links, separated by commas. Each link is a
 * URI-Reference enclosed in angle brackets followed by zero or more {@code ;}-separated
 * target attributes ({@code name=value} pairs):
 *
 * <pre>{@code
 *   Link: <https://api.example.com/users?page=2>; rel="next"; title="Next page",
 *         <https://api.example.com/users?page=34>; rel="last"
 * }</pre>
 *
 * <p>Attribute values may be either RFC 7230 tokens or quoted strings; quoted strings are
 * unquoted and their backslash escapes decoded, so a quoted value may itself contain commas
 * and semicolons without being mistaken for a separator. Attribute (parameter) names are
 * case-insensitive and stored lower-cased; per RFC 8288 §3 the first occurrence of a repeated
 * attribute wins and later occurrences are ignored. Insertion order is otherwise preserved.
 *
 * <p>Parsing is <strong>lenient</strong>: a comma-separated entry that does not begin with a
 * {@code <uri-ref>} is skipped rather than aborting the whole header, and stray characters are
 * tolerated where possible. Passing {@code null} or a blank string yields an empty result.
 *
 * <p>The class performs no network or file I/O and is therefore fully offline-testable.
 * Instances are immutable.
 */
public final class LinkHeader {

    private final List<Link> links;

    private LinkHeader(List<Link> links) {
        this.links = links;
    }

    /**
     * A single web link: the URI-Reference from inside the angle brackets plus its target
     * attributes. Attribute names are held case-insensitively (lower-cased); values are stored
     * verbatim with surrounding quotes removed and {@code \} escapes decoded.
     *
     * @param uri    the URI-Reference (the text that appeared between {@code <} and {@code >})
     * @param params the target attributes, keyed by lower-cased attribute name
     */
    public record Link(String uri, Map<String, String> params) {

        /** Canonical constructor: lower-cases attribute names and makes the map unmodifiable. */
        public Link {
            Objects.requireNonNull(uri, "uri");
            Map<String, String> copy = new LinkedHashMap<>();
            if (params != null) {
                for (Map.Entry<String, String> e : params.entrySet()) {
                    copy.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
            }
            params = Collections.unmodifiableMap(copy);
        }

        /**
         * The value of the named target attribute, matched case-insensitively.
         *
         * @param name attribute name (e.g. {@code "rel"}); may be {@code null}
         * @return the attribute value, or {@code null} if absent
         */
        public String param(String name) {
            return name == null ? null : params.get(name.toLowerCase(Locale.ROOT));
        }

        /** The raw {@code rel} attribute value, or {@code null} if none. May hold several
         * space-separated relation types — see {@link #rels()}. */
        public String rel() {
            return param("rel");
        }

        /**
         * The {@code rel} attribute split into individual relation types on whitespace. An
         * absent or blank {@code rel} yields an empty list.
         *
         * @return the relation types, in order, never {@code null}
         */
        public List<String> rels() {
            String r = rel();
            if (r == null || r.isBlank()) {
                return List.of();
            }
            return List.of(r.trim().split("\\s+"));
        }

        /** The {@code title} attribute, or {@code null} if none. */
        public String title() {
            return param("title");
        }

        /** The {@code type} (media type hint) attribute, or {@code null} if none. */
        public String type() {
            return param("type");
        }

        /** The {@code hreflang} attribute, or {@code null} if none. */
        public String hreflang() {
            return param("hreflang");
        }

        /**
         * Whether this link advertises the given relation type (case-insensitive), taking the
         * space-separated {@code rel} values into account.
         *
         * @param rel the relation type to test for
         * @return {@code true} if {@code rel} is one of this link's relation types
         */
        public boolean hasRel(String rel) {
            if (rel == null) {
                return false;
            }
            for (String r : rels()) {
                if (r.equalsIgnoreCase(rel)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Parses a {@code Link} header value into its links.
     *
     * <p>Multiple {@code Link} header lines from a response may simply be joined with commas
     * and passed as a single string. Parsing is lenient (see the class documentation).
     *
     * @param input the raw {@code Link} header value; may be {@code null} or blank
     * @return an immutable {@code LinkHeader} holding the parsed links (possibly empty)
     */
    public static LinkHeader parse(String input) {
        if (input == null) {
            return new LinkHeader(List.of());
        }
        List<Link> result = new ArrayList<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            // Skip any leading whitespace and empty comma-separated slots.
            while (i < n && (isWs(input.charAt(i)) || input.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            if (input.charAt(i) != '<') {
                // Lenient: an entry with no <uri-ref> is skipped entirely.
                i = skipToComma(input, i);
                continue;
            }
            int close = input.indexOf('>', i + 1);
            if (close < 0) {
                break; // Unterminated URI-Reference; nothing sensible remains.
            }
            String uri = input.substring(i + 1, close);
            i = close + 1;

            Map<String, String> params = new LinkedHashMap<>();
            boolean done = false;
            while (!done) {
                while (i < n && isWs(input.charAt(i))) {
                    i++;
                }
                if (i >= n) {
                    break;
                }
                char c = input.charAt(i);
                if (c == ',') {
                    break; // End of this link; next iteration handles the following one.
                }
                if (c != ';') {
                    // Unexpected token after the URI; recover by skipping to the next link.
                    i = skipToComma(input, i);
                    break;
                }
                i++; // consume ';'
                while (i < n && isWs(input.charAt(i))) {
                    i++;
                }
                int nameStart = i;
                while (i < n) {
                    char pc = input.charAt(i);
                    if (pc == '=' || pc == ';' || pc == ',' || isWs(pc)) {
                        break;
                    }
                    i++;
                }
                String name = input.substring(nameStart, i);
                while (i < n && isWs(input.charAt(i))) {
                    i++;
                }
                String value = "";
                if (i < n && input.charAt(i) == '=') {
                    i++; // consume '='
                    while (i < n && isWs(input.charAt(i))) {
                        i++;
                    }
                    if (i < n && input.charAt(i) == '"') {
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
                            if (vc == ';' || vc == ',' || isWs(vc)) {
                                break;
                            }
                            i++;
                        }
                        value = input.substring(valStart, i);
                    }
                }
                if (!name.isEmpty()) {
                    // First occurrence of a repeated attribute wins (RFC 8288 §3).
                    params.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
                }
            }
            result.add(new Link(uri, params));
        }
        return new LinkHeader(Collections.unmodifiableList(result));
    }

    /** The parsed links, in document order; never {@code null}, possibly empty. */
    public List<Link> links() {
        return links;
    }

    /** Whether no links were parsed. */
    public boolean isEmpty() {
        return links.isEmpty();
    }

    /** The number of parsed links. */
    public int size() {
        return links.size();
    }

    /** The first parsed link, if any. */
    public Optional<Link> first() {
        return links.isEmpty() ? Optional.empty() : Optional.of(links.get(0));
    }

    /**
     * The first link that advertises the given relation type (case-insensitive), honouring
     * space-separated {@code rel} values. Handy for pagination relations such as
     * {@code next}, {@code prev}, {@code first} and {@code last}.
     *
     * @param rel the relation type to search for
     * @return the matching link, or an empty {@link Optional} if none matches
     */
    public Optional<Link> byRel(String rel) {
        if (rel == null) {
            return Optional.empty();
        }
        for (Link l : links) {
            if (l.hasRel(rel)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t';
    }

    private static int skipToComma(String s, int from) {
        int i = from;
        while (i < s.length() && s.charAt(i) != ',') {
            i++;
        }
        return i;
    }
}
