package com.nexuslink.protocol.ldap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure, offline, dependency-free parser for RFC 4515 LDAP search filters. It is the inverse of
 * {@link LdapFilterBuilder}: given a filter string such as {@code (&(objectClass=person)(cn=jd*))}
 * it produces a typed {@link LdapFilter} tree that can {@link LdapFilter#render()} an equivalent
 * filter back out (round-trip).
 *
 * <p>Recursive-descent implementation of the RFC 4515 §2 grammar. Assertion values are decoded
 * per RFC 4515 §3 &mdash; each {@code \HH} escape becomes the character with hex value {@code HH},
 * and the reserved bytes {@code ( ) * \ NUL} must be escaped (an unescaped occurrence is rejected).
 * Malformed input (unbalanced parentheses, empty filter, missing/bad operator, invalid escape)
 * raises {@link LdapFilterParseException}.
 *
 * <p>No directory connection is involved; this is the tested logic behind the UI's filter field.
 */
public final class LdapFilterParser {

    private final String src;
    private int pos;

    private LdapFilterParser(String src) {
        this.src = src;
    }

    /**
     * Parse a complete RFC 4515 filter string into its {@link LdapFilter} tree.
     *
     * @throws LdapFilterParseException if the input is not a single well-formed filter
     */
    public static LdapFilter parse(String filter) {
        Objects.requireNonNull(filter, "filter");
        if (filter.isBlank()) {
            throw new LdapFilterParseException("Empty filter");
        }
        LdapFilterParser parser = new LdapFilterParser(filter);
        LdapFilter root = parser.parseFilter();
        if (parser.pos != filter.length()) {
            throw new LdapFilterParseException(
                    "Trailing characters after filter at position " + parser.pos + ": " + filter);
        }
        return root;
    }

    // --- grammar: filter = "(" filtercomp ")" (RFC 4515 §2) --------------------------------------

    private LdapFilter parseFilter() {
        expect('(');
        if (pos >= src.length()) {
            throw new LdapFilterParseException("Unbalanced '(' at end of filter: " + src);
        }
        char c = src.charAt(pos);
        LdapFilter node = switch (c) {
            case '&' -> {
                pos++;
                yield new LdapFilter.And(parseFilterList());
            }
            case '|' -> {
                pos++;
                yield new LdapFilter.Or(parseFilterList());
            }
            case '!' -> {
                pos++;
                if (pos >= src.length() || src.charAt(pos) != '(') {
                    throw new LdapFilterParseException("'!' must be followed by a filter: " + src);
                }
                yield new LdapFilter.Not(parseFilter());
            }
            default -> parseItem(readItem());
        };
        expect(')');
        return node;
    }

    /** filterlist = 1*filter (RFC 4515 §2) — at least one child is required. */
    private List<LdapFilter> parseFilterList() {
        List<LdapFilter> children = new ArrayList<>();
        while (pos < src.length() && src.charAt(pos) == '(') {
            children.add(parseFilter());
        }
        if (children.isEmpty()) {
            throw new LdapFilterParseException("Filter list must contain at least one filter: " + src);
        }
        return children;
    }

    /** Read the raw text of an item filter (everything up to the closing ')'). */
    private String readItem() {
        int start = pos;
        while (pos < src.length() && src.charAt(pos) != ')' && src.charAt(pos) != '(') {
            pos++;
        }
        if (pos >= src.length()) {
            throw new LdapFilterParseException("Unbalanced '(' — missing ')': " + src);
        }
        if (src.charAt(pos) == '(') {
            throw new LdapFilterParseException("Unexpected '(' inside filter item: " + src);
        }
        return src.substring(start, pos);
    }

    // --- grammar: item = simple / present / substring / extensible (RFC 4515 §2) -----------------

    private LdapFilter parseItem(String item) {
        int eq = item.indexOf('=');
        if (eq < 0) {
            throw new LdapFilterParseException("Filter item has no '=' operator: (" + item + ")");
        }
        if (eq == 0) {
            throw new LdapFilterParseException("Filter item has no attribute: (" + item + ")");
        }
        char before = item.charAt(eq - 1);
        String rawValue = item.substring(eq + 1);
        return switch (before) {
            case '~' -> new LdapFilter.Approx(attribute(item.substring(0, eq - 1)), decode(rawValue));
            case '>' -> new LdapFilter.GreaterOrEqual(
                    attribute(item.substring(0, eq - 1)), decode(rawValue));
            case '<' -> new LdapFilter.LessOrEqual(
                    attribute(item.substring(0, eq - 1)), decode(rawValue));
            case ':' -> parseExtensible(item.substring(0, eq - 1), rawValue);
            default -> parseSimpleOrSubstring(item.substring(0, eq), rawValue);
        };
    }

    /** present / simple(=) / substring — distinguished by the (unescaped) '*' characters. */
    private LdapFilter parseSimpleOrSubstring(String attr, String rawValue) {
        String attribute = attribute(attr);
        if (rawValue.equals("*")) {
            return new LdapFilter.Present(attribute);
        }
        List<String> segments = splitOnStar(rawValue);
        if (segments.size() == 1) {
            return new LdapFilter.Equality(attribute, decode(rawValue));
        }
        String initial = decode(segments.get(0));
        String fin = decode(segments.get(segments.size() - 1));
        List<String> any = new ArrayList<>();
        for (int i = 1; i < segments.size() - 1; i++) {
            any.add(decode(segments.get(i)));
        }
        return new LdapFilter.Substring(attribute, initial, any, fin);
    }

    /**
     * extensible = ( attr [":dn"] [":" matchingrule] ":=" value )
     *            / ( [":dn"] ":" matchingrule ":=" value )   (RFC 4515 §2)
     *
     * <p>{@code left} is the text before the {@code ":="}, i.e. {@code attr}, {@code dn} and
     * {@code matchingrule} tokens separated by ':' (with a possibly-empty leading attribute).
     */
    private LdapFilter parseExtensible(String left, String rawValue) {
        String[] tokens = left.split(":", -1);
        String attr = tokens[0];
        boolean dnAttributes = false;
        String matchingRule = null;
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isEmpty()) {
                throw new LdapFilterParseException("Empty component in extensible match: " + left);
            }
            if (token.equals("dn")) {
                if (dnAttributes) {
                    throw new LdapFilterParseException("Duplicate ':dn' in extensible match: " + left);
                }
                dnAttributes = true;
            } else if (matchingRule == null) {
                matchingRule = token;
            } else {
                throw new LdapFilterParseException("Multiple matching rules in extensible match: " + left);
            }
        }
        String attribute = attr.isEmpty() ? null : attribute(attr);
        if (attribute == null && matchingRule == null) {
            throw new LdapFilterParseException(
                    "Extensible match needs an attribute or a matching rule: " + left);
        }
        return new LdapFilter.ExtensibleMatch(attribute, matchingRule, dnAttributes, decode(rawValue));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new LdapFilterParseException(
                    "Expected '" + c + "' at position " + pos + ": " + src);
        }
        pos++;
    }

    /** Split on literal '*' (which, per RFC 4515, is always a substring delimiter — never escaped). */
    private static List<String> splitOnStar(String value) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '*') {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    /**
     * Validate an attribute description (RFC 4512 §2.5: descr or numericoid, plus ';' options). The
     * check is lenient: non-empty, starts with a letter or digit, and contains only letters, digits,
     * '-', '.', or ';'.
     */
    private static String attribute(String attr) {
        if (attr.isEmpty()) {
            throw new LdapFilterParseException("Empty attribute description");
        }
        char first = attr.charAt(0);
        if (!isLetter(first) && !isDigit(first)) {
            throw new LdapFilterParseException("Invalid attribute description: " + attr);
        }
        for (int i = 0; i < attr.length(); i++) {
            char c = attr.charAt(i);
            if (!isLetter(c) && !isDigit(c) && c != '-' && c != '.' && c != ';') {
                throw new LdapFilterParseException("Invalid attribute description: " + attr);
            }
        }
        return attr;
    }

    /**
     * Decode an assertion value per RFC 4515 §3: resolve every {@code \HH} escape to the character
     * with that hex value, and reject any unescaped reserved byte ({@code ( ) * } or NUL).
     */
    private static String decode(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (i + 2 >= value.length()) {
                        throw new LdapFilterParseException(
                                "Truncated '\\HH' escape in value: " + value);
                    }
                    int hi = hexValue(value.charAt(i + 1));
                    int lo = hexValue(value.charAt(i + 2));
                    if (hi < 0 || lo < 0) {
                        throw new LdapFilterParseException(
                                "Invalid '\\HH' escape in value: " + value);
                    }
                    sb.append((char) ((hi << 4) | lo));
                    i += 2;
                }
                case '(', ')', '*' -> throw new LdapFilterParseException(
                        "Unescaped '" + c + "' in assertion value: " + value);
                case '\u0000' -> throw new LdapFilterParseException(
                        "Unescaped NUL in assertion value: " + value);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Thrown when a filter string is not valid per RFC 4515. */
    public static final class LdapFilterParseException extends RuntimeException {
        public LdapFilterParseException(String message) {
            super(message);
        }
    }
}
