package com.nexuslink.protocol.redis;

/**
 * The glob-style pattern matching Redis uses for {@code KEYS} / {@code SCAN MATCH}, implemented
 * client-side so listings can be filtered without a round-trip. Mirrors Redis's {@code stringmatchlen}:
 * <ul>
 *   <li>{@code *} matches any (possibly empty) run of characters,</li>
 *   <li>{@code ?} matches exactly one character,</li>
 *   <li>{@code [...]} a character class — ranges ({@code a-z}) and a leading {@code ^} to negate,</li>
 *   <li>{@code \x} escapes the next character (matched literally),</li>
 *   <li>any other character matches itself.</li>
 * </ul>
 * Matching is over the whole string (not a sub-match) and is case-sensitive, like Redis.
 */
public final class RedisGlob {

    private RedisGlob() {}

    /** Whether {@code key} matches the Redis glob {@code pattern}. */
    public static boolean matches(String pattern, String key) {
        return match(pattern, 0, key, 0);
    }

    private static boolean match(String p, int pi, String s, int si) {
        while (pi < p.length()) {
            char pc = p.charAt(pi);
            switch (pc) {
                case '*' -> {
                    // Collapse consecutive '*'.
                    while (pi + 1 < p.length() && p.charAt(pi + 1) == '*') pi++;
                    if (pi + 1 == p.length()) return true;           // trailing '*' matches the rest
                    for (int k = si; k <= s.length(); k++) {
                        if (match(p, pi + 1, s, k)) return true;
                    }
                    return false;
                }
                case '?' -> {
                    if (si >= s.length()) return false;
                    si++;
                    pi++;
                }
                case '[' -> {
                    if (si >= s.length()) return false;
                    int close = pi + 1;
                    boolean negate = close < p.length() && p.charAt(close) == '^';
                    if (negate) close++;
                    boolean matched = false;
                    char c = s.charAt(si);
                    while (close < p.length() && p.charAt(close) != ']') {
                        if (p.charAt(close) == '\\' && close + 1 < p.length()) {
                            close++;
                            if (p.charAt(close) == c) matched = true;
                        } else if (close + 2 < p.length() && p.charAt(close + 1) == '-'
                                && p.charAt(close + 2) != ']') {
                            char lo = p.charAt(close);
                            char hi = p.charAt(close + 2);
                            if (lo > hi) { char t = lo; lo = hi; hi = t; }
                            if (c >= lo && c <= hi) matched = true;
                            close += 2;
                        } else if (p.charAt(close) == c) {
                            matched = true;
                        }
                        close++;
                    }
                    if (negate) matched = !matched;
                    if (!matched) return false;
                    si++;
                    pi = close < p.length() ? close + 1 : close;    // skip past ']'
                }
                case '\\' -> {
                    if (pi + 1 < p.length()) pi++;                   // escaped literal
                    if (si >= s.length() || s.charAt(si) != p.charAt(pi)) return false;
                    si++;
                    pi++;
                }
                default -> {
                    if (si >= s.length() || s.charAt(si) != pc) return false;
                    si++;
                    pi++;
                }
            }
        }
        return si == s.length();
    }
}
