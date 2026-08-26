package com.nexuslink.core.search;

import java.util.Locale;

/**
 * The matching rule behind the sidebar's type-to-filter boxes: does this item match what the user
 * has typed so far, and how well?
 *
 * <p>Three ways to match, in descending quality, so short queries stay useful without becoming
 * noisy:
 * <ol>
 *   <li><b>Prefix</b> — the text starts with the query ({@code "kaf"} → "Kafka").</li>
 *   <li><b>Word start</b> — a word inside the text starts with it ({@code "blob"} → "Azure Blob"),
 *       or the query matches the item's initials ({@code "sb"} → "Service Bus").</li>
 *   <li><b>Substring</b> — the query appears anywhere ({@code "post"} → "jdbc:postgresql://…").</li>
 * </ol>
 * A multi-word query must match every word somewhere in the text, in any order, so
 * {@code "prod kafka"} finds "Kafka — prod cluster". Matching is case- and accent-insensitive for
 * ASCII, and a blank query matches everything, which is what an empty search box should do.
 *
 * <p>Pure and dependency-free so both sidebar filters share one behaviour and it can be tested
 * without a UI.
 */
public final class QuickFilter {

    /** No match at all. */
    public static final int NO_MATCH = 0;

    private QuickFilter() {}

    /** {@code true} when {@code text} matches {@code query} by any of the three rules. */
    public static boolean matches(String query, String text) {
        return score(query, text) > NO_MATCH;
    }

    /**
     * How well {@code text} matches {@code query}: higher is better, {@link #NO_MATCH} for no match.
     * Use it to sort matches so the most obvious one is first.
     */
    public static int score(String query, String text) {
        if (query == null || query.isBlank()) return 1;      // an empty box matches everything
        if (text == null || text.isEmpty()) return NO_MATCH;
        String haystack = normalize(text);
        int total = 0;
        for (String word : normalize(query).split("\\s+")) {
            if (word.isEmpty()) continue;
            int s = scoreWord(word, haystack);
            if (s == NO_MATCH) return NO_MATCH;              // every query word must match
            total += s;
        }
        return total == 0 ? 1 : total;
    }

    /** The score for one query word against an already-normalised haystack. */
    private static int scoreWord(String word, String haystack) {
        if (haystack.startsWith(word)) return 100;
        int at = haystack.indexOf(word);
        if (at > 0 && isWordBoundary(haystack.charAt(at - 1))) return 60;
        if (initials(haystack).startsWith(word)) return 40;
        return at >= 0 ? 20 : NO_MATCH;
    }

    /** The first letter of each word, so "Azure Service Bus" can be found by typing "asb". */
    private static String initials(String text) {
        StringBuilder sb = new StringBuilder();
        boolean atStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isWordBoundary(c)) { atStart = true; continue; }
            if (atStart) sb.append(c);
            atStart = false;
        }
        return sb.toString();
    }

    private static boolean isWordBoundary(char c) {
        return !Character.isLetterOrDigit(c);
    }

    /** Lower-cased, accent-stripped text — so "Solace" and "solacé" filter alike. */
    private static String normalize(String s) {
        String lower = s.toLowerCase(Locale.ROOT).trim();
        return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
}
