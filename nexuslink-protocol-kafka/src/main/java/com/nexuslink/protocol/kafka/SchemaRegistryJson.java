package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader/writer covering exactly what the {@link SchemaRegistryClient}
 * needs from Confluent Schema Registry responses: arrays of strings, arrays of integers, and flat
 * objects whose values are strings (one of which — the {@code schema} field — is itself an escaped
 * JSON document) or numbers. {@link #parse} returns a tree of {@code Map<String,Object>},
 * {@code List<Object>}, {@link String}, {@link Long}, {@link Double}, {@link Boolean} and {@code null};
 * {@link #quote} encodes a string with surrounding quotes for a request body. Pure and offline-testable.
 */
public final class SchemaRegistryJson {

    private final String s;
    private int i;

    private SchemaRegistryJson(String s) { this.s = s; }

    /** Parses a JSON document into a tree of maps/lists/scalars. */
    public static Object parse(String json) {
        SchemaRegistryJson p = new SchemaRegistryJson(json);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != p.s.length()) throw new IllegalArgumentException("Trailing content at index " + p.i);
        return v;
    }

    /** JSON-encodes {@code value} as a quoted string (null becomes {@code "null"} text). */
    public static String quote(String value) {
        String v = value == null ? "" : value;
        StringBuilder b = new StringBuilder(v.length() + 2);
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
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
        if (peek() == '}') { i++; return m; }
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
        if (peek() == ']') { i++; return list; }
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
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("boolean");
    }

    private Object nul() {
        if (s.startsWith("null", i)) { i += 4; return null; }
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
        return new IllegalArgumentException("Malformed JSON: expected " + expected + " at index " + i);
    }
}
