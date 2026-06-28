package com.nexuslink.protocol.ldap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A single Relative Distinguished Name (RDN) — one or more {@code type=value} attribute-value
 * assertions joined by {@code '+'} (multi-valued RDN). Values are held unescaped; escaping per
 * RFC 4514 is applied only when rendering back to string form.
 */
public final class Rdn {

    /** One {@code type=value} assertion within an RDN. */
    public record Ava(String type, String value) {
        public Ava {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
        }
    }

    private final List<Ava> avas;

    private Rdn(List<Ava> avas) {
        if (avas.isEmpty()) {
            throw new IllegalArgumentException("RDN must have at least one attribute");
        }
        this.avas = List.copyOf(avas);
    }

    /** Parse a single RDN such as {@code "cn=John Smith"} or {@code "cn=John+sn=Smith"}. */
    public static Rdn parse(String text) {
        Objects.requireNonNull(text, "text");
        List<Ava> parsed = new ArrayList<>();
        for (String part : splitUnescaped(text, '+')) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Empty attribute-value assertion in RDN: " + text);
            }
            int eq = indexOfUnescaped(part, '=');
            if (eq < 0) {
                throw new IllegalArgumentException("Missing '=' in RDN component: " + part);
            }
            String type = part.substring(0, eq).trim();
            String rawValue = trimValue(part.substring(eq + 1));
            if (type.isEmpty()) {
                throw new IllegalArgumentException("Empty attribute type in RDN component: " + part);
            }
            parsed.add(new Ava(type, unescapeValue(rawValue)));
        }
        return new Rdn(parsed);
    }

    /** Build an RDN from a single type/value pair (value given unescaped). */
    public static Rdn of(String type, String value) {
        return new Rdn(List.of(new Ava(type, value)));
    }

    /** The attribute-value assertions making up this RDN (order preserved). */
    public List<Ava> avas() {
        return avas;
    }

    /** True if this RDN has more than one attribute-value assertion. */
    public boolean isMultiValued() {
        return avas.size() > 1;
    }

    /** Canonical render: types as given, values escaped per RFC 4514, AVAs joined with {@code '+'}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < avas.size(); i++) {
            if (i > 0) {
                sb.append('+');
            }
            Ava a = avas.get(i);
            sb.append(a.type()).append('=').append(escapeValue(a.value()));
        }
        return sb.toString();
    }

    /**
     * Normalized form used for equality: attribute types lower-cased, values escaped, AVAs sorted so
     * that AVA ordering within a multi-valued RDN is not significant.
     */
    String normalized() {
        List<String> parts = new ArrayList<>();
        for (Ava a : avas) {
            parts.add(a.type().toLowerCase(Locale.ROOT) + '=' + escapeValue(a.value()));
        }
        parts.sort(null);
        return String.join("+", parts);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rdn other && normalized().equals(other.normalized());
    }

    @Override
    public int hashCode() {
        return normalized().hashCode();
    }

    // --- escaping helpers (RFC 4514) -------------------------------------------------------------

    /** Escape an attribute value for use in a DN/RDN string. */
    static String escapeValue(String value) {
        if (value.isEmpty()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean first = i == 0;
            boolean last = i == value.length() - 1;
            switch (c) {
                case '"', '+', ',', ';', '<', '>', '\\' -> sb.append('\\').append(c);
                case '#' -> {
                    if (first) {
                        sb.append('\\').append(c);
                    } else {
                        sb.append(c);
                    }
                }
                case ' ' -> {
                    if (first || last) {
                        sb.append('\\').append(c);
                    } else {
                        sb.append(c);
                    }
                }
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reverse {@link #escapeValue}, also accepting {@code \HH} hex escapes. */
    static String unescapeValue(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(i + 1);
                if (isHex(n) && i + 2 < value.length() && isHex(value.charAt(i + 2))) {
                    sb.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    sb.append(n);
                    i += 1;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Trim insignificant surrounding spaces from a DN value, preserving an escaped trailing space. */
    static String trimValue(String s) {
        int start = 0;
        while (start < s.length() && s.charAt(start) == ' ') {
            start++;
        }
        int end = s.length();
        while (end > start && s.charAt(end - 1) == ' ' && !isEscaped(s, end - 1)) {
            end--;
        }
        return s.substring(start, end);
    }

    /** True if the char at {@code idx} is escaped by an odd number of preceding backslashes. */
    private static boolean isEscaped(String s, int idx) {
        int backslashes = 0;
        for (int i = idx - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Split on an unescaped delimiter, keeping escaped occurrences intact. */
    static List<String> splitUnescaped(String text, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                cur.append(c).append(text.charAt(i + 1));
                i++;
            } else if (c == delim) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static int indexOfUnescaped(String text, char target) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }
}
