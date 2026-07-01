package com.nexuslink.protocol.http.rest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure, dependency-free parser and model for an HTTP media type / {@code Content-Type}
 * value as defined by RFC 7231 §3.1.1.1:
 *
 * <pre>{@code
 *   media-type = type "/" subtype *( OWS ";" OWS parameter )
 *   parameter  = token "=" ( token / quoted-string )
 * }</pre>
 *
 * <p>Both {@code type} and {@code subtype} are matched case-insensitively and are stored
 * lower-cased. Parameter names are likewise case-insensitive and stored lower-cased, while
 * parameter values are kept verbatim (with any surrounding quotes removed and {@code \}
 * escapes decoded). Insertion order of parameters is preserved so the original ordering can
 * be re-rendered by {@link #toString()}.
 *
 * <p>The class performs no network or file I/O and is therefore fully offline-testable.
 * Instances are immutable.
 */
public final class MediaType {

    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;

    private MediaType(String type, String subtype, Map<String, String> parameters) {
        this.type = type;
        this.subtype = subtype;
        this.parameters = parameters;
    }

    /**
     * Parses a media-type string such as {@code "text/html; charset=UTF-8"}.
     *
     * <p>Surrounding optional whitespace (OWS) is tolerated, as is OWS around the {@code ;}
     * parameter separators. Parameter values may be either RFC 7230 tokens or quoted strings;
     * quoted strings are unquoted and their backslash escapes decoded.
     *
     * @param input the raw media-type / {@code Content-Type} value
     * @return the parsed, immutable {@code MediaType}
     * @throws MediaTypeParseException if {@code input} is null or malformed
     */
    public static MediaType parse(String input) {
        if (input == null) {
            throw new MediaTypeParseException("media type must not be null");
        }
        String s = input.trim();
        if (s.isEmpty()) {
            throw new MediaTypeParseException("media type must not be empty");
        }

        int semi = s.indexOf(';');
        String essence = (semi < 0 ? s : s.substring(0, semi)).trim();

        int slash = essence.indexOf('/');
        if (slash < 0) {
            throw new MediaTypeParseException("missing '/' in media type: " + input);
        }
        String type = essence.substring(0, slash).trim();
        String subtype = essence.substring(slash + 1).trim();
        if (type.isEmpty()) {
            throw new MediaTypeParseException("empty type in media type: " + input);
        }
        if (subtype.isEmpty()) {
            throw new MediaTypeParseException("empty subtype in media type: " + input);
        }
        if (subtype.indexOf('/') >= 0) {
            throw new MediaTypeParseException("too many '/' in media type: " + input);
        }
        requireToken(type, "type", input);
        requireToken(subtype, "subtype", input);

        Map<String, String> params = new LinkedHashMap<>();
        if (semi >= 0) {
            parseParameters(s, semi, params, input);
        }

        return new MediaType(
                type.toLowerCase(Locale.ROOT),
                subtype.toLowerCase(Locale.ROOT),
                params);
    }

    /**
     * Builds a media type directly from its components without parsing. The type and subtype
     * are lower-cased; parameter names are lower-cased and their iteration order is preserved.
     *
     * @throws MediaTypeParseException if the type, subtype or any parameter name is not a valid
     *                                 token
     */
    public static MediaType of(String type, String subtype, Map<String, String> parameters) {
        if (type == null || subtype == null) {
            throw new MediaTypeParseException("type and subtype must not be null");
        }
        String t = type.trim();
        String st = subtype.trim();
        if (t.isEmpty()) {
            throw new MediaTypeParseException("empty type");
        }
        if (st.isEmpty()) {
            throw new MediaTypeParseException("empty subtype");
        }
        requireToken(t, "type", type + "/" + subtype);
        requireToken(st, "subtype", type + "/" + subtype);

        Map<String, String> params = new LinkedHashMap<>();
        if (parameters != null) {
            for (Map.Entry<String, String> e : parameters.entrySet()) {
                String name = e.getKey();
                if (name == null || name.trim().isEmpty()) {
                    throw new MediaTypeParseException("empty parameter name");
                }
                requireToken(name.trim(), "parameter name", name);
                params.put(name.trim().toLowerCase(Locale.ROOT),
                        e.getValue() == null ? "" : e.getValue());
            }
        }
        return new MediaType(t.toLowerCase(Locale.ROOT), st.toLowerCase(Locale.ROOT), params);
    }

    /** The lower-cased primary type, e.g. {@code "text"} for {@code text/html}. */
    public String type() {
        return type;
    }

    /** The lower-cased subtype, e.g. {@code "html"} for {@code text/html}. */
    public String subtype() {
        return subtype;
    }

    /**
     * Returns the value of the named parameter, matched case-insensitively, or {@code null} if
     * absent. Values are returned already unquoted.
     */
    public String parameter(String name) {
        if (name == null) {
            return null;
        }
        return parameters.get(name.toLowerCase(Locale.ROOT));
    }

    /** An immutable, order-preserving view of all parameters (lower-cased names). */
    public Map<String, String> parameters() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /** The {@code charset} parameter, if present. */
    public Optional<String> charset() {
        return Optional.ofNullable(parameter("charset"));
    }

    /** The {@code boundary} parameter (for {@code multipart/*} bodies), if present. */
    public Optional<String> boundary() {
        return Optional.ofNullable(parameter("boundary"));
    }

    /** True if the primary type is {@code multipart}. */
    public boolean isMultipart() {
        return "multipart".equals(type);
    }

    /** True if the primary type is {@code text}. */
    public boolean isText() {
        return "text".equals(type);
    }

    /** True if either the type or the subtype is the {@code *} wildcard. */
    public boolean isWildcard() {
        return "*".equals(type) || "*".equals(subtype);
    }

    /**
     * The {@code type/subtype} essence with no parameters, e.g. {@code "text/html"}.
     */
    public String essence() {
        return type + "/" + subtype;
    }

    /**
     * Tests whether this concrete media type is matched by {@code pattern}, where {@code *} in
     * the pattern's type and/or subtype acts as a wildcard. For example {@code application/json}
     * matches the patterns {@code application/json}, {@code application/*} and {@code *}{@code /*}.
     *
     * <p>Matching considers only type and subtype; parameters are ignored, which is the common
     * behaviour required for HTTP {@code Accept} negotiation.
     *
     * @param pattern the (possibly wildcarded) pattern to test against
     * @return true if this media type is covered by {@code pattern}
     */
    public boolean matches(MediaType pattern) {
        if (pattern == null) {
            return false;
        }
        boolean typeOk = pattern.type.equals("*") || pattern.type.equals(this.type);
        boolean subtypeOk = pattern.subtype.equals("*") || pattern.subtype.equals(this.subtype);
        return typeOk && subtypeOk;
    }

    /**
     * Re-renders this media type as {@code type/subtype; k=v; ...}, quoting each parameter value
     * only when it is not a valid token. Parsing the result reproduces an equal instance.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(essence());
        for (Map.Entry<String, String> e : parameters.entrySet()) {
            sb.append("; ").append(e.getKey()).append('=').append(renderValue(e.getValue()));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MediaType other)) {
            return false;
        }
        return type.equals(other.type)
                && subtype.equals(other.subtype)
                && parameters.equals(other.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, subtype, parameters);
    }

    // ------------------------------------------------------------------
    // Parsing internals
    // ------------------------------------------------------------------

    /**
     * Parses the parameter list beginning at the first {@code ;} (index {@code start} in {@code s}).
     * Later duplicate names overwrite earlier ones while keeping the earlier position, mirroring how
     * most HTTP stacks treat repeated parameters.
     */
    private static void parseParameters(String s, int start, Map<String, String> params, String input) {
        int i = start;
        int n = s.length();
        while (i < n) {
            // s.charAt(i) is ';'
            i++; // consume ';'
            i = skipOws(s, i);
            if (i >= n) {
                // Trailing ';' with nothing after it (allowing OWS) is malformed.
                throw new MediaTypeParseException("trailing ';' with no parameter: " + input);
            }

            // Parameter name (token) up to '='.
            int nameStart = i;
            while (i < n && s.charAt(i) != '=' && s.charAt(i) != ';') {
                i++;
            }
            if (i >= n || s.charAt(i) == ';') {
                throw new MediaTypeParseException("parameter without '=': " + input);
            }
            String name = s.substring(nameStart, i).trim();
            if (name.isEmpty()) {
                throw new MediaTypeParseException("empty parameter name: " + input);
            }
            requireToken(name, "parameter name", input);
            i++; // consume '='
            i = skipOws(s, i);

            String value;
            if (i < n && s.charAt(i) == '"') {
                // Quoted string.
                StringBuilder v = new StringBuilder();
                i++; // consume opening quote
                boolean closed = false;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '\\') {
                        if (i + 1 >= n) {
                            throw new MediaTypeParseException(
                                    "dangling escape in quoted parameter: " + input);
                        }
                        v.append(s.charAt(i + 1));
                        i += 2;
                    } else if (c == '"') {
                        closed = true;
                        i++; // consume closing quote
                        break;
                    } else {
                        v.append(c);
                        i++;
                    }
                }
                if (!closed) {
                    throw new MediaTypeParseException("unterminated quoted parameter: " + input);
                }
                value = v.toString();
                i = skipOws(s, i);
                if (i < n && s.charAt(i) != ';') {
                    throw new MediaTypeParseException(
                            "unexpected text after quoted parameter: " + input);
                }
            } else {
                // Token value up to the next ';'.
                int valStart = i;
                while (i < n && s.charAt(i) != ';') {
                    i++;
                }
                value = s.substring(valStart, i).trim();
                if (value.isEmpty()) {
                    throw new MediaTypeParseException("empty parameter value: " + input);
                }
                requireToken(value, "parameter value", input);
            }
            params.put(name.toLowerCase(Locale.ROOT), value);
            // Loop continues: s.charAt(i), if present, is ';'.
        }
    }

    private static int skipOws(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /**
     * Validates that {@code value} is a non-empty RFC 7230 {@code token} (the character set shared by
     * media-type components and unquoted parameters).
     */
    private static void requireToken(String value, String what, String input) {
        for (int i = 0; i < value.length(); i++) {
            if (!isTokenChar(value.charAt(i))) {
                throw new MediaTypeParseException(
                        "illegal character in " + what + " ('" + value.charAt(i) + "'): " + input);
            }
        }
    }

    /** RFC 7230 {@code tchar}: {@code "!#$%&'*+-.^_`|~"} / DIGIT / ALPHA. */
    private static boolean isTokenChar(char c) {
        if (c >= 'a' && c <= 'z') {
            return true;
        }
        if (c >= 'A' && c <= 'Z') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            return true;
        }
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /**
     * Renders a parameter value, emitting a bare token when possible and otherwise a quoted string
     * with {@code "} and {@code \} escaped.
     */
    private static String renderValue(String value) {
        boolean needsQuote = value.isEmpty();
        if (!needsQuote) {
            for (int i = 0; i < value.length(); i++) {
                if (!isTokenChar(value.charAt(i))) {
                    needsQuote = true;
                    break;
                }
            }
        }
        if (!needsQuote) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Thrown when a media-type string cannot be parsed: a missing {@code /}, an empty type or
     * subtype, or a malformed parameter.
     */
    public static final class MediaTypeParseException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        MediaTypeParseException(String message) {
            super(message);
        }
    }
}
