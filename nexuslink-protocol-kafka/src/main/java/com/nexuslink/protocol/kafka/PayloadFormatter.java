package com.nexuslink.protocol.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Pure, total helpers for rendering a Kafka record's key/value in a chosen display format.
 * Every method is null/blank safe and never throws — unrecognised input is returned unchanged,
 * so the UI can switch formats freely without risk of an exception escaping into the table.
 *
 * <p>This module ships with no JSON library, so the JSON pretty-printer is a small hand-rolled,
 * tolerant recursive-descent reformatter: it validates and re-indents well-formed JSON and falls
 * back to the raw text for anything it cannot parse.
 */
public final class PayloadFormatter {

    private PayloadFormatter() {}

    /** Display formats offered in the consume toolbar. */
    public enum Format { STRING, JSON, HEX, BASE64 }

    private static final String INDENT = "  ";

    /** Formats {@code raw} for display using {@code fmt}; null-safe and never throws. */
    public static String format(String raw, Format fmt) {
        String s = raw == null ? "" : raw;
        if (fmt == null) return s;
        switch (fmt) {
            case JSON:   return prettyJson(s);
            case HEX:    return hex(s);
            case BASE64: return base64(s);
            case STRING:
            default:     return s;
        }
    }

    /** Pretty-prints {@code raw} when it parses as JSON, otherwise returns it unchanged. */
    public static String prettyJson(String raw) {
        if (raw == null) return "";
        if (raw.isBlank()) return raw;
        try {
            JsonPrinter p = new JsonPrinter(raw);
            String out = p.parse();
            return out == null ? raw : out;
        } catch (RuntimeException e) {
            return raw; // tolerant: any malformed input is shown as-is
        }
    }

    /** Compact, space-separated hex of the UTF-8 bytes of {@code raw} (empty for null/empty). */
    public static String hex(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            int v = bytes[i] & 0xFF;
            sb.append(Character.forDigit(v >>> 4, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    /** Base64 (standard) encoding of the UTF-8 bytes of {@code raw} (empty for null/empty). */
    public static String base64(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal recursive-descent JSON reformatter. Re-emits string and number/literal tokens
     * verbatim (preserving fidelity) and only normalises whitespace/indentation. Throws on any
     * structural error so the caller can fall back to the raw text.
     */
    private static final class JsonPrinter {
        private final String s;
        private int i;
        private final StringBuilder out = new StringBuilder();

        JsonPrinter(String s) { this.s = s; }

        /** @return pretty-printed JSON, or {@code null} if the trimmed input is empty. */
        String parse() {
            skipWs();
            if (i >= s.length()) return null;
            value(0);
            skipWs();
            if (i != s.length()) throw new IllegalStateException("trailing content");
            return out.toString();
        }

        private void value(int depth) {
            skipWs();
            if (i >= s.length()) throw new IllegalStateException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': object(depth); break;
                case '[': array(depth);  break;
                case '"': out.append(string()); break;
                default:  out.append(literal()); break;
            }
        }

        private void object(int depth) {
            expect('{');
            skipWs();
            if (peek() == '}') { i++; out.append("{}"); return; }
            out.append("{\n");
            boolean first = true;
            while (true) {
                if (!first) out.append(",\n");
                first = false;
                skipWs();
                indent(depth + 1);
                out.append(string());
                skipWs();
                expect(':');
                out.append(": ");
                value(depth + 1);
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw new IllegalStateException("expected , or }");
            }
            out.append('\n');
            indent(depth);
            out.append('}');
        }

        private void array(int depth) {
            expect('[');
            skipWs();
            if (peek() == ']') { i++; out.append("[]"); return; }
            out.append("[\n");
            boolean first = true;
            while (true) {
                if (!first) out.append(",\n");
                first = false;
                indent(depth + 1);
                value(depth + 1);
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw new IllegalStateException("expected , or ]");
            }
            out.append('\n');
            indent(depth);
            out.append(']');
        }

        /** Consumes and returns a JSON string token verbatim, including the surrounding quotes. */
        private String string() {
            if (peek() != '"') throw new IllegalStateException("expected string");
            int start = i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalStateException("bad escape");
                    i++; // skip escaped char
                } else if (c == '"') {
                    return s.substring(start, i);
                } else if (c < 0x20) {
                    throw new IllegalStateException("control char in string");
                }
            }
            throw new IllegalStateException("unterminated string");
        }

        /** Consumes a number / true / false / null token verbatim and validates it minimally. */
        private String literal() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                i++;
            }
            String tok = s.substring(start, i);
            if (tok.isEmpty()) throw new IllegalStateException("empty token");
            if (tok.equals("true") || tok.equals("false") || tok.equals("null")) return tok;
            // must be a JSON number
            if (!tok.matches("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")) {
                throw new IllegalStateException("invalid token: " + tok);
            }
            return tok;
        }

        private void indent(int depth) { out.append(INDENT.repeat(depth)); }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw new IllegalStateException("unexpected end");
            return s.charAt(i);
        }

        private char next() {
            if (i >= s.length()) throw new IllegalStateException("unexpected end");
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) throw new IllegalStateException("expected " + c);
        }
    }
}
