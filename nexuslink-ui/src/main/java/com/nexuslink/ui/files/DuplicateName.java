package com.nexuslink.ui.files;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes a non-colliding name for a "Duplicate" of a file, the way a commander does it: the first
 * duplicate of {@code report.txt} is {@code report copy.txt}, then {@code report copy 2.txt},
 * {@code report copy 3.txt}, … Duplicating something that is already a copy increments the counter
 * ({@code report copy.txt} → {@code report copy 2.txt}) rather than stacking suffixes. The extension
 * (text after the last dot, when not a leading dot) is preserved, matching {@link BulkRename}'s
 * extension handling, so dotfiles and extensionless names are handled too. Pure and JavaFX-free.
 */
public final class DuplicateName {

    /** Matches a base ending in " copy" or " copy N", capturing the stem and the optional number. */
    private static final Pattern COPY_SUFFIX = Pattern.compile("(.*) copy(?: (\\d+))?");

    private DuplicateName() {}

    /** The first candidate not already present in {@code existing}. */
    public static String of(String name, Collection<String> existing) {
        Set<String> taken = Set.copyOf(existing);
        return of(name, taken::contains);
    }

    /**
     * The first candidate name for which {@code exists} returns false, starting from {@code "<stem> copy"}
     * (or the next number when {@code name} is itself already a copy).
     */
    public static String of(String name, Predicate<String> exists) {
        int dot = extensionDot(name);
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);   // includes the leading '.'

        String stem = base;
        int start = 1;   // n == 1 renders as " copy" (no number)
        Matcher m = COPY_SUFFIX.matcher(base);
        if (m.matches()) {
            stem = m.group(1);
            start = m.group(2) == null ? 2 : Integer.parseInt(m.group(2)) + 1;
        }
        for (int n = start; ; n++) {
            String candidate = (n == 1 ? stem + " copy" : stem + " copy " + n) + ext;
            if (!exists.test(candidate)) return candidate;
        }
    }

    /** Index of the extension dot, or -1 when there is none (also -1 for dotfiles like ".bashrc"). */
    private static int extensionDot(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? -1 : dot;
    }
}
