package com.nexuslink.protocol.http.rest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * A tiny, dependency-free pre-request scripting DSL for the REST client. It runs <em>before</em> a
 * request is sent and computes values (timestamps, UUIDs, HMAC signatures, …) that the request then
 * references via {@code ${VAR}} in its URL, headers, or body.
 *
 * <p>Nashorn/JS is not available on Java&nbsp;21, so rather than embed a scripting engine this is a
 * deliberately small line-oriented language:
 *
 * <pre>{@code
 *   # comments start with '#' or '//'
 *   set TS       = now()
 *   set STAMP    = isoNow()
 *   set NONCE    = uuid()
 *   set AUTH     = 'v1:' + hmacSha256(${API_SECRET}, ${TS} + ':' + NONCE)
 *   set PICK     = randomInt(1, 100)
 * }</pre>
 *
 * <p>Each statement is {@code set NAME = <expr>}. An expression is a {@code +}-concatenation of
 * terms, where a term is a single-/double-quoted string literal, a {@code ${NAME}} reference (to a
 * previously-set var or an existing environment var), or a call to one of the built-in functions:
 *
 * <ul>
 *   <li>{@code now()} — current epoch milliseconds</li>
 *   <li>{@code isoNow()} — current time as an ISO-8601 UTC instant</li>
 *   <li>{@code uuid()} — a random UUID</li>
 *   <li>{@code base64(x)} — Base64 of {@code x} (UTF-8)</li>
 *   <li>{@code hmacSha256(key, message)} — HMAC-SHA256, hex output. {@code key} is decoded as hex
 *       when it is a non-empty even-length hex string, otherwise used as UTF-8 text.</li>
 *   <li>{@code randomInt(min, max)} — a random integer in {@code [min, max]} (inclusive)</li>
 * </ul>
 *
 * <p>Parse and evaluation problems are collected as human-readable messages rather than thrown, so
 * the UI can surface every issue at once; each good {@code set} still contributes its variable.
 * Evaluation is left-to-right, so a later statement may reference an earlier one.
 */
public final class PreRequestScript {

    private PreRequestScript() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** The outcome of a run: the variables produced (in declaration order) and any error messages. */
    public static final class Result {
        private final Map<String, String> variables;
        private final List<String> errors;

        Result(Map<String, String> variables, List<String> errors) {
            this.variables = variables;
            this.errors = errors;
        }

        /** Variables produced, keyed by name, in declaration order. Never {@code null}. */
        public Map<String, String> variables() {
            return variables;
        }

        /** Human-readable parse/evaluation errors, in source order. Empty when the script was clean. */
        public List<String> errors() {
            return errors;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** Thrown internally by the evaluator; caught per line and recorded as an error message. */
    private static final class EvalException extends RuntimeException {
        EvalException(String message) {
            super(message);
        }
    }

    /**
     * Runs {@code script} against a base variable resolver (typically the active environment).
     *
     * @param script      the script text; {@code null}/blank yields an empty result
     * @param baseResolver resolves {@code ${NAME}} references not set earlier in the script; may be
     *                     {@code null}. Returning {@code null} means "unknown".
     */
    public static Result run(String script, Function<String, String> baseResolver) {
        Map<String, String> vars = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return new Result(vars, errors);
        }

        Function<String, String> resolver = name -> {
            if (vars.containsKey(name)) return vars.get(name);
            return baseResolver == null ? null : baseResolver.apply(name);
        };

        String[] lines = script.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int lineNo = i + 1;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

            if (!line.startsWith("set ") && !line.startsWith("set\t")) {
                errors.add("Line " + lineNo + ": expected 'set NAME = <expr>'");
                continue;
            }
            String rest = line.substring(3).strip();
            int eq = rest.indexOf('=');
            if (eq <= 0) {
                errors.add("Line " + lineNo + ": expected 'set NAME = <expr>'");
                continue;
            }
            String name = rest.substring(0, eq).strip();
            String exprText = rest.substring(eq + 1).strip();
            if (!isIdentifier(name)) {
                errors.add("Line " + lineNo + ": invalid variable name '" + name + "'");
                continue;
            }
            try {
                String value = new Parser(exprText, resolver).parseFully();
                vars.put(name, value);
            } catch (EvalException e) {
                errors.add("Line " + lineNo + ": " + e.getMessage());
            }
        }
        return new Result(vars, errors);
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    // ---- expression parser / evaluator --------------------------------------

    /**
     * Recursive-descent evaluator over one expression. Grammar:
     * <pre>
     *   expr := term ('+' term)*
     *   term := stringLit | numberLit | varRef | funcCall
     * </pre>
     */
    private static final class Parser {
        private final String src;
        private final Function<String, String> resolver;
        private int pos;

        Parser(String src, Function<String, String> resolver) {
            this.src = src;
            this.resolver = resolver;
        }

        String parseFully() {
            String v = expr();
            skipWs();
            if (pos < src.length()) {
                throw new EvalException("unexpected '" + src.charAt(pos) + "' at column " + (pos + 1));
            }
            return v;
        }

        private String expr() {
            StringBuilder sb = new StringBuilder(term());
            skipWs();
            while (pos < src.length() && src.charAt(pos) == '+') {
                pos++;                // consume '+'
                sb.append(term());
                skipWs();
            }
            return sb.toString();
        }

        private String term() {
            skipWs();
            if (pos >= src.length()) throw new EvalException("unexpected end of expression");
            char c = src.charAt(pos);
            if (c == '\'' || c == '"') return stringLiteral(c);
            if (c == '$' && pos + 1 < src.length() && src.charAt(pos + 1) == '{') return varRef();
            if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length()
                    && Character.isDigit(src.charAt(pos + 1)))) return numberLiteral();
            if (Character.isLetter(c) || c == '_') return funcCall();
            throw new EvalException("unexpected '" + c + "' at column " + (pos + 1));
        }

        private String stringLiteral(char quote) {
            pos++;                    // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '\\' && pos < src.length()) {
                    char n = src.charAt(pos++);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        default -> sb.append(n);   // \\, \', \", and any other escape → literal char
                    }
                    continue;
                }
                if (c == quote) return sb.toString();
                sb.append(c);
            }
            throw new EvalException("unterminated string literal");
        }

        private String numberLiteral() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            return src.substring(start, pos);
        }

        private String varRef() {
            pos += 2;                 // consume "${"
            int close = src.indexOf('}', pos);
            if (close < 0) throw new EvalException("unterminated '${' reference");
            String name = src.substring(pos, close).strip();
            pos = close + 1;
            if (name.isEmpty()) throw new EvalException("empty '${}' reference");
            String v = resolver == null ? null : resolver.apply(name);
            return v == null ? "" : v;
        }

        private String funcCall() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            String name = src.substring(start, pos);
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '(') {
                throw new EvalException("expected '(' after '" + name + "'");
            }
            pos++;                    // consume '('
            List<String> args = new ArrayList<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ')') {
                pos++;                // empty arg list
            } else {
                args.add(expr());
                skipWs();
                while (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;            // consume ','
                    args.add(expr());
                    skipWs();
                }
                if (pos >= src.length() || src.charAt(pos) != ')') {
                    throw new EvalException("expected ')' to close '" + name + "('");
                }
                pos++;                // consume ')'
            }
            return apply(name, args);
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }
    }

    private static String apply(String name, List<String> args) {
        switch (name) {
            case "now":
                requireArgs(name, args, 0);
                return Long.toString(System.currentTimeMillis());
            case "isoNow":
                requireArgs(name, args, 0);
                return ISO.format(Instant.now());
            case "uuid":
                requireArgs(name, args, 0);
                return UUID.randomUUID().toString();
            case "base64":
                requireArgs(name, args, 1);
                return Base64.getEncoder()
                        .encodeToString(args.get(0).getBytes(StandardCharsets.UTF_8));
            case "hmacSha256":
                requireArgs(name, args, 2);
                return hmacSha256(args.get(0), args.get(1));
            case "randomInt":
                requireArgs(name, args, 2);
                return randomInt(args.get(0), args.get(1));
            default:
                throw new EvalException("unknown function '" + name + "'");
        }
    }

    private static void requireArgs(String name, List<String> args, int n) {
        if (args.size() != n) {
            throw new EvalException(name + "() expects " + n + " argument"
                    + (n == 1 ? "" : "s") + ", got " + args.size());
        }
    }

    private static String hmacSha256(String key, String message) {
        byte[] keyBytes = isHex(key)
                ? hexToBytes(key) : key.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes.length == 0 ? new byte[1] : keyBytes, "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new EvalException("hmacSha256 failed: " + e.getMessage());
        }
    }

    private static String randomInt(String minText, String maxText) {
        long min = parseLong(minText, "randomInt min");
        long max = parseLong(maxText, "randomInt max");
        if (min > max) throw new EvalException("randomInt min (" + min + ") > max (" + max + ")");
        long span = max - min + 1;          // inclusive
        long r = Math.floorMod(RANDOM.nextLong(), span);
        return Long.toString(min + r);
    }

    private static long parseLong(String text, String what) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            throw new EvalException(what + " must be an integer, got '" + text + "'");
        }
    }

    private static boolean isHex(String s) {
        if (s.isEmpty() || (s.length() & 1) == 1) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static byte[] hexToBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4)
                    | Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
              .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
