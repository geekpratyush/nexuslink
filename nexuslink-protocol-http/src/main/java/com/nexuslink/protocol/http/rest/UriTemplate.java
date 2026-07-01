package com.nexuslink.protocol.http.rest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, dependency-free expander for URI Templates as defined by
 * <a href="https://www.rfc-editor.org/rfc/rfc6570">RFC 6570</a> (Levels 1&ndash;3).
 *
 * <p>A URI Template is a string containing literal text interspersed with brace-delimited
 * {@code {expression}}s. Each expression names one or more template variables and, optionally,
 * a leading <em>operator</em> that selects an expansion style. Expansion substitutes the
 * variables with values taken from a caller-supplied map, percent-encoding each value according
 * to the operator's allowed character set.
 *
 * <p>The following operators are supported (RFC 6570 &sect;2.2, Appendix A):
 * <pre>
 *   {var}     Level 1  simple string expansion, percent-encode all but <em>unreserved</em>
 *   {+var}    Level 2  reserved expansion, leave <em>reserved</em> + <em>unreserved</em> unescaped
 *   {#var}    Level 2  fragment expansion, prefixes '#'
 *   {x,y}     Level 3  multiple variables joined by ','
 *   {.x}      Level 3  label expansion, prefixes and separates with '.'
 *   {/x}      Level 3  path-segment expansion, prefixes and separates with '/'
 *   {;x}      Level 3  path-style parameter expansion (name;name=value)
 *   {?x,y}    Level 3  form-style query, prefixes '?', separates with '&', name=value
 *   {&x}      Level 3  form-style query continuation, prefixes '&'
 * </pre>
 *
 * <p>Value modifiers (RFC 6570 &sect;2.4) are supported: the prefix modifier {@code {var:3}}
 * takes the first three characters of a string value, and the explode modifier {@code {var*}}
 * expands each member of a list or map individually.
 *
 * <p>A variable value may be a {@link String} (or any scalar rendered via {@code toString()}),
 * a {@link List} (multi-valued list), or a {@link Map} (associative array). A variable that is
 * absent, {@code null}, or an empty list/map is treated as <em>undefined</em> and omitted along
 * with any separator it would have introduced (RFC 6570 &sect;3.2.1).
 *
 * <p>The class performs no network or file I/O and is therefore fully offline-testable.
 * Instances are immutable and thread-safe.
 */
public final class UriTemplate {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String template;
    private final List<Object> parts; // each element is a String literal or an Expression

    /**
     * Parses and validates a URI Template so it can be expanded repeatedly.
     *
     * @param template the RFC 6570 template string
     * @throws UriTemplateException if the template is null or malformed
     */
    public UriTemplate(String template) {
        if (template == null) {
            throw new UriTemplateException("template must not be null");
        }
        this.template = template;
        this.parts = parse(template);
    }

    /**
     * Convenience one-shot: parses {@code template} and expands it against {@code vars}.
     *
     * @param template the RFC 6570 template string
     * @param vars     variable bindings; may be null (treated as empty)
     * @return the expanded URI reference
     * @throws UriTemplateException if the template is malformed
     */
    public static String expand(String template, Map<String, Object> vars) {
        return new UriTemplate(template).expand(vars);
    }

    /** @return the original, unparsed template string. */
    public String template() {
        return template;
    }

    /**
     * Expands this template against the given variable bindings.
     *
     * @param vars variable bindings; may be null (treated as empty)
     * @return the expanded URI reference
     */
    public String expand(Map<String, Object> vars) {
        Map<String, Object> v = (vars == null) ? Map.of() : vars;
        StringBuilder out = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof String literal) {
                out.append(literal);
            } else {
                expandExpression((Expression) part, v, out);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Expansion (RFC 6570 §3.2)
    // ------------------------------------------------------------------------------------------

    private void expandExpression(Expression e, Map<String, Object> vars, StringBuilder out) {
        Operator op = e.operator;
        boolean first = true;
        for (VarSpec vs : e.varSpecs) {
            Object value = vars.get(vs.name);
            if (isUndefined(value)) {
                continue;
            }
            out.append(first ? op.first : op.sep);
            out.append(expandVarSpec(op, vs, value));
            first = false;
        }
    }

    private String expandVarSpec(Operator op, VarSpec vs, Object value) {
        if (value instanceof Map<?, ?> map) {
            if (vs.prefix >= 0) {
                throw new UriTemplateException(
                        "prefix modifier ':' cannot apply to the composite variable '" + vs.name + "'");
            }
            return expandMap(op, vs, map);
        }
        if (value instanceof List<?> list) {
            if (vs.prefix >= 0) {
                throw new UriTemplateException(
                        "prefix modifier ':' cannot apply to the composite variable '" + vs.name + "'");
            }
            return expandList(op, vs, list);
        }
        // Scalar value.
        String s = stringOf(value);
        if (vs.prefix >= 0) {
            int cps = s.codePointCount(0, s.length());
            if (vs.prefix < cps) {
                s = s.substring(0, s.offsetByCodePoints(0, vs.prefix));
            }
        }
        return formatNamedOrBare(op, vs.name, s);
    }

    /** Renders a single scalar value with (for named operators) its {@code name=} prefix. */
    private String formatNamedOrBare(Operator op, String name, String rawValue) {
        if (op.named) {
            if (rawValue.isEmpty()) {
                return name + op.ifEmpty;
            }
            return name + "=" + encode(rawValue, op.allowReserved);
        }
        return encode(rawValue, op.allowReserved);
    }

    private String expandList(Operator op, VarSpec vs, List<?> list) {
        StringBuilder sb = new StringBuilder();
        if (vs.explode) {
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(op.sep);
                }
                first = false;
                String raw = stringOf(item);
                if (op.named) {
                    sb.append(vs.name);
                    if (raw.isEmpty()) {
                        sb.append(op.ifEmpty);
                    } else {
                        sb.append('=').append(encode(raw, op.allowReserved));
                    }
                } else {
                    sb.append(encode(raw, op.allowReserved));
                }
            }
            return sb.toString();
        }
        // No explode: join members with ',' (RFC 6570 §3.2.1).
        String joined = joinCommaEncoded(list, op.allowReserved);
        if (op.named) {
            return vs.name + "=" + joined;
        }
        return joined;
    }

    private String expandMap(Operator op, VarSpec vs, Map<?, ?> map) {
        StringBuilder sb = new StringBuilder();
        if (vs.explode) {
            // Exploded associative array: each key becomes its own name=value pair.
            boolean first = true;
            for (Map.Entry<?, ?> en : map.entrySet()) {
                if (!first) {
                    sb.append(op.sep);
                }
                first = false;
                sb.append(encode(stringOf(en.getKey()), op.allowReserved));
                String val = stringOf(en.getValue());
                if (val.isEmpty()) {
                    sb.append(op.ifEmpty);
                } else {
                    sb.append('=').append(encode(val, op.allowReserved));
                }
            }
            return sb.toString();
        }
        // No explode: flatten to key,value,key,value (RFC 6570 §3.2.1).
        boolean first = true;
        for (Map.Entry<?, ?> en : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(encode(stringOf(en.getKey()), op.allowReserved));
            sb.append(',');
            sb.append(encode(stringOf(en.getValue()), op.allowReserved));
        }
        if (op.named) {
            return vs.name + "=" + sb;
        }
        return sb.toString();
    }

    private String joinCommaEncoded(List<?> list, boolean allowReserved) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(encode(stringOf(item), allowReserved));
        }
        return sb.toString();
    }

    private static boolean isUndefined(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof List<?> l) {
            return l.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    private static String stringOf(Object o) {
        return (o == null) ? "" : o.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Percent-encoding (RFC 6570 §3.2.1, RFC 3986 §2)
    // ------------------------------------------------------------------------------------------

    private static String encode(String value, boolean allowReserved) {
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        int n = value.length();
        while (i < n) {
            char c = value.charAt(i);
            // In reserved expansion, an already pct-encoded triple is copied verbatim.
            if (allowReserved && c == '%' && i + 2 < n
                    && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                sb.append(c).append(value.charAt(i + 1)).append(value.charAt(i + 2));
                i += 3;
                continue;
            }
            if (isUnreserved(c) || (allowReserved && isReserved(c))) {
                sb.append(c);
                i++;
                continue;
            }
            int cp = value.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~" (RFC 3986 §2.3). */
    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /** reserved = gen-delims / sub-delims (RFC 3986 §2.2). */
    private static boolean isReserved(char c) {
        switch (c) {
            case ':': case '/': case '?': case '#': case '[': case ']': case '@':
            case '!': case '$': case '&': case '\'': case '(': case ')':
            case '*': case '+': case ',': case ';': case '=':
                return true;
            default:
                return false;
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ------------------------------------------------------------------------------------------
    // Parsing (RFC 6570 §2)
    // ------------------------------------------------------------------------------------------

    private static List<Object> parse(String template) {
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close < 0) {
                    throw new UriTemplateException("unbalanced '{' in template: " + template);
                }
                String expr = template.substring(i + 1, close);
                if (expr.indexOf('{') >= 0) {
                    throw new UriTemplateException("nested '{' inside expression: " + template);
                }
                if (literal.length() > 0) {
                    parts.add(encodeLiteral(literal.toString()));
                    literal.setLength(0);
                }
                parts.add(parseExpression(expr));
                i = close + 1;
            } else if (c == '}') {
                throw new UriTemplateException("unbalanced '}' in template: " + template);
            } else {
                literal.append(c);
                i++;
            }
        }
        if (literal.length() > 0) {
            parts.add(encodeLiteral(literal.toString()));
        }
        return parts;
    }

    /**
     * Copies literal text verbatim where the characters are permitted in a URI (reserved,
     * unreserved, or an existing pct-encoded triple) and percent-encodes anything else
     * (RFC 6570 &sect;3.1).
     */
    private static String encodeLiteral(String literal) {
        StringBuilder sb = new StringBuilder(literal.length());
        int i = 0;
        int n = literal.length();
        while (i < n) {
            char c = literal.charAt(i);
            if (c == '%' && i + 2 < n && isHex(literal.charAt(i + 1)) && isHex(literal.charAt(i + 2))) {
                sb.append(c).append(literal.charAt(i + 1)).append(literal.charAt(i + 2));
                i += 3;
                continue;
            }
            if (isUnreserved(c) || isReserved(c)) {
                sb.append(c);
                i++;
                continue;
            }
            int cp = literal.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                sb.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static Expression parseExpression(String expr) {
        if (expr.isEmpty()) {
            throw new UriTemplateException("empty expression '{}'");
        }
        char opChar = expr.charAt(0);
        Operator op = Operator.fromSymbol(opChar);
        String body;
        if (op != null) {
            body = expr.substring(1);
        } else if ("=,!@|".indexOf(opChar) >= 0) {
            // Operators reserved for future extensions (RFC 6570 §2.2).
            throw new UriTemplateException("unsupported/reserved operator '" + opChar + "' in {" + expr + "}");
        } else {
            op = Operator.NONE;
            body = expr;
        }
        if (body.isEmpty()) {
            throw new UriTemplateException("expression has no variables: {" + expr + "}");
        }
        List<VarSpec> specs = new ArrayList<>();
        for (String piece : body.split(",", -1)) {
            specs.add(parseVarSpec(piece));
        }
        return new Expression(op, specs);
    }

    private static VarSpec parseVarSpec(String spec) {
        int star = spec.indexOf('*');
        int colon = spec.indexOf(':');
        if (star >= 0 && colon >= 0) {
            throw new UriTemplateException("varspec cannot combine ':' and '*': " + spec);
        }
        if (star >= 0) {
            if (star != spec.length() - 1) {
                throw new UriTemplateException("explode '*' must be the final character: " + spec);
            }
            String name = spec.substring(0, star);
            validateName(name, spec);
            return new VarSpec(name, -1, true);
        }
        if (colon >= 0) {
            String name = spec.substring(0, colon);
            validateName(name, spec);
            String digits = spec.substring(colon + 1);
            if (digits.isEmpty() || digits.length() > 4) {
                throw new UriTemplateException("prefix length must be 1-4 digits: " + spec);
            }
            for (int k = 0; k < digits.length(); k++) {
                if (digits.charAt(k) < '0' || digits.charAt(k) > '9') {
                    throw new UriTemplateException("prefix length must be numeric: " + spec);
                }
            }
            return new VarSpec(name, Integer.parseInt(digits), false);
        }
        validateName(spec, spec);
        return new VarSpec(spec, -1, false);
    }

    /** varname = varchar *( ["."] varchar ), varchar = ALPHA / DIGIT / "_" / pct-encoded. */
    private static void validateName(String name, String spec) {
        if (name.isEmpty()) {
            throw new UriTemplateException("empty variable name in varspec: " + spec);
        }
        int i = 0;
        int n = name.length();
        while (i < n) {
            char c = name.charAt(i);
            if (c == '%') {
                if (i + 2 >= n || !isHex(name.charAt(i + 1)) || !isHex(name.charAt(i + 2))) {
                    throw new UriTemplateException("invalid pct-encoding in variable name: " + spec);
                }
                i += 3;
                continue;
            }
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.';
            if (!ok) {
                throw new UriTemplateException("illegal character '" + c + "' in variable name: " + spec);
            }
            i++;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------------------------------

    /** Expansion operator with its associated character-string behaviour (RFC 6570 Appendix A). */
    private enum Operator {
        NONE('\0', "", ",", false, "", false),
        PLUS('+', "", ",", false, "", true),
        HASH('#', "#", ",", false, "", true),
        DOT('.', ".", ".", false, "", false),
        SLASH('/', "/", "/", false, "", false),
        SEMI(';', ";", ";", true, "", false),
        QUERY('?', "?", "&", true, "=", false),
        AMP('&', "&", "&", true, "=", false);

        final char symbol;
        final String first;
        final String sep;
        final boolean named;
        final String ifEmpty;
        final boolean allowReserved;

        Operator(char symbol, String first, String sep, boolean named, String ifEmpty, boolean allowReserved) {
            this.symbol = symbol;
            this.first = first;
            this.sep = sep;
            this.named = named;
            this.ifEmpty = ifEmpty;
            this.allowReserved = allowReserved;
        }

        static Operator fromSymbol(char c) {
            for (Operator o : values()) {
                if (o != NONE && o.symbol == c) {
                    return o;
                }
            }
            return null;
        }
    }

    /** A single variable specification within an expression, e.g. {@code var}, {@code var:3}, {@code var*}. */
    private static final class VarSpec {
        final String name;
        final int prefix;   // -1 when no prefix modifier
        final boolean explode;

        VarSpec(String name, int prefix, boolean explode) {
            this.name = name;
            this.prefix = prefix;
            this.explode = explode;
        }
    }

    /** A parsed {@code {...}} expression: an operator plus one or more variable specifications. */
    private static final class Expression {
        final Operator operator;
        final List<VarSpec> varSpecs;

        Expression(Operator operator, List<VarSpec> varSpecs) {
            this.operator = operator;
            this.varSpecs = varSpecs;
        }
    }

    /** Thrown when a URI Template is syntactically malformed (unbalanced braces, unknown operator, etc.). */
    public static final class UriTemplateException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public UriTemplateException(String message) {
            super(Objects.requireNonNull(message, "message"));
        }
    }
}
