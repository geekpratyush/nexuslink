package com.nexuslink.protocol.redis;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a console command line into the argument list Redis expects, the way {@code redis-cli}
 * does: whitespace separates arguments, but a double- or single-quoted run is one argument, and
 * inside double quotes {@code \\n}, {@code \\t}, {@code \\"} and {@code \\xNN} are unescaped.
 *
 * <p>Without this, {@code SET greeting "hello world"} would set the value {@code "hello} — quoting
 * is not decoration, it is how a value with a space gets through. Pure and dependency-free, so the
 * parsing rules can be tested on their own.
 */
public final class RedisCommandLine {

    private RedisCommandLine() {}

    /**
     * Parses {@code line} into arguments — the command name first, then its arguments.
     *
     * @throws RedisCommandLineException if a quote is never closed
     */
    public static List<String> parse(String line) {
        List<String> args = new ArrayList<>();
        if (line == null) return args;

        StringBuilder current = new StringBuilder();
        boolean inArg = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inArg) { args.add(current.toString()); current.setLength(0); inArg = false; }
                i++;
                continue;
            }
            inArg = true;
            if (c == '"') {
                i = readDoubleQuoted(line, i + 1, current);
            } else if (c == '\'') {
                i = readSingleQuoted(line, i + 1, current);
            } else {
                current.append(c);
                i++;
            }
        }
        if (inArg) args.add(current.toString());
        return args;
    }

    /** Reads a double-quoted run, honouring escapes; returns the index just past the closing quote. */
    private static int readDoubleQuoted(String line, int from, StringBuilder out) {
        int i = from;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') return i + 1;
            if (c == '\\' && i + 1 < line.length()) {
                char e = line.charAt(i + 1);
                switch (e) {
                    case 'n' -> { out.append('\n'); i += 2; }
                    case 'r' -> { out.append('\r'); i += 2; }
                    case 't' -> { out.append('\t'); i += 2; }
                    case 'b' -> { out.append('\b'); i += 2; }
                    case 'x' -> {
                        if (i + 3 < line.length() && isHex(line.charAt(i + 2)) && isHex(line.charAt(i + 3))) {
                            out.append((char) Integer.parseInt(line.substring(i + 2, i + 4), 16));
                            i += 4;
                        } else { out.append('x'); i += 2; }
                    }
                    default -> { out.append(e); i += 2; }
                }
                continue;
            }
            out.append(c);
            i++;
        }
        throw new RedisCommandLineException("Unbalanced double quote");
    }

    /** Reads a single-quoted run; only {@code \\'} is an escape, as in redis-cli. */
    private static int readSingleQuoted(String line, int from, StringBuilder out) {
        int i = from;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\'') return i + 1;
            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                out.append('\'');
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        throw new RedisCommandLineException("Unbalanced single quote");
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Thrown when a command line cannot be split — an unbalanced quote. */
    public static final class RedisCommandLineException extends RuntimeException {
        public RedisCommandLineException(String message) { super(message); }
    }
}
