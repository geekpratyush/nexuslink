package com.nexuslink.protocol.http.rest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes a compact-serialization JSON Web Token (JWT / JWS) for <strong>inspection only</strong>.
 *
 * <p><strong>This decoder never verifies the signature.</strong> It exists so the REST client can
 * offer an "inspect bearer token" affordance — showing a human what a token claims to be — and for
 * nothing else. It performs no cryptography, contacts no key server, and makes no trust decision;
 * the signature segment is preserved verbatim (see {@link DecodedJwt#signature()}) but is treated
 * as opaque. Never use the result of {@link #decode(String)} to authenticate or authorize a
 * request: a forged or unsigned token decodes exactly like a genuine one.
 *
 * <p>Decoding splits the token on {@code .} into header, payload and signature; Base64URL-decodes
 * (the {@code -_} alphabet, padding optional) the header and payload to their JSON text; and
 * parses that JSON with a small hand-rolled reader — the module carries no JSON dependency, in the
 * same spirit as {@code HarExporter} and the Kafka {@code SchemaRegistryJson}. The registered
 * time claims {@code exp}/{@code iat}/{@code nbf} are read as NumericDate seconds.
 *
 * <p>Pure and offline: no I/O, no network, no clock. Structurally invalid input — the wrong number
 * of segments, a segment that is not valid Base64URL, or a header/payload that is not a JSON
 * object — raises {@link JwtDecodeException}.
 */
public final class JwtDecoder {

    private JwtDecoder() {}

    /**
     * Decodes {@code token} into its header, payload and (unverified) signature.
     *
     * @param token a compact JWS, {@code header.payload.signature}
     * @return the decoded, unverified contents
     * @throws JwtDecodeException if {@code token} is null, lacks exactly three {@code .}-separated
     *     segments, has a non-Base64URL header or payload, or a header/payload that is not a JSON
     *     object
     */
    public static DecodedJwt decode(String token) {
        if (token == null) {
            throw new JwtDecodeException("Token is null");
        }
        // Keep trailing empty segments so a missing signature is still counted as a segment.
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtDecodeException(
                    "A JWT must have exactly 3 dot-separated segments but found " + parts.length);
        }

        String headerJson = decodeSegmentToJson(parts[0], "header");
        String payloadJson = decodeSegmentToJson(parts[1], "payload");
        String signature = parts[2];

        Map<String, Object> header = parseJsonObject(headerJson, "header");
        Map<String, Object> payload = parseJsonObject(payloadJson, "payload");

        return new DecodedJwt(headerJson, payloadJson, signature, header, payload);
    }

    private static String decodeSegmentToJson(String segment, String which) {
        byte[] bytes = base64UrlDecode(segment, which);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> parseJsonObject(String json, String which) {
        Object tree;
        try {
            tree = new JsonReader(json).readDocument();
        } catch (RuntimeException e) {
            throw new JwtDecodeException("JWT " + which + " is not valid JSON: " + e.getMessage(), e);
        }
        if (!(tree instanceof Map<?, ?>)) {
            throw new JwtDecodeException("JWT " + which + " must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) tree;
        return obj;
    }

    /**
     * Base64URL-decodes a JWT segment (unpadded, {@code -_} alphabet). Package-visible so
     * {@link DecodedJwt#signatureBytes()} can lazily decode the retained signature string.
     *
     * @throws JwtDecodeException if the text is not valid Base64URL
     */
    static byte[] base64UrlDecode(String segment) {
        return base64UrlDecode(segment, "segment");
    }

    private static byte[] base64UrlDecode(String segment, String which) {
        try {
            return Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new JwtDecodeException("JWT " + which + " is not valid Base64URL", e);
        }
    }

    /**
     * A minimal recursive-descent JSON reader, dependency-free. It yields a tree of
     * {@code Map<String,Object>} (insertion-ordered), {@code List<Object>}, {@link String},
     * {@link Long}, {@link Double}, {@link Boolean} and {@code null}. Only what JWT inspection
     * needs — it is intentionally not a full-spec validator.
     */
    private static final class JsonReader {

        private final String s;
        private int i;

        JsonReader(String s) {
            this.s = s;
        }

        Object readDocument() {
            skipWs();
            Object v = value();
            skipWs();
            if (i != s.length()) {
                throw new IllegalArgumentException("trailing content at index " + i);
            }
            return v;
        }

        private Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, value());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw err("',' or '}'");
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value());
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw err("',' or ']'");
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("4 hex digits");
                            String hex = s.substring(i, i + 4);
                            i += 4;
                            b.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw err("valid escape");
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            if (num.isEmpty()) throw err("a value");
            if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        private Boolean bool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw err("boolean");
        }

        private Object nul() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw err("null");
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private char peek() {
            if (i >= s.length()) throw err("more input");
            return s.charAt(i);
        }

        private char next() {
            if (i >= s.length()) throw err("more input");
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) throw err("'" + c + "'");
        }

        private IllegalArgumentException err(String expected) {
            return new IllegalArgumentException("expected " + expected + " at index " + i);
        }
    }
}
