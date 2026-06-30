package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Splits a file-system path into clickable breadcrumb segments — root → … → current — by walking up
 * via the owning {@link FileSystem}'s {@code parent} function, so it works for both POSIX-style remote
 * paths ("/home/user") and local OS paths. Kept JavaFX-free so it is unit-testable.
 */
public final class PathCrumbs {

    /** One breadcrumb: the {@code label} shown to the user and the full {@code path} it navigates to. */
    public record Crumb(String label, String path) {}

    private PathCrumbs() {}

    /**
     * Returns the breadcrumbs for {@code path}, ordered root-first. Each crumb's path is an ancestor
     * (or the path itself) and its label is that segment's name (the root crumb keeps the full root
     * token, e.g. "/"). Returns an empty list for a null/blank path.
     */
    public static List<Crumb> of(String path, UnaryOperator<String> parent) {
        if (path == null || path.isBlank()) return List.of();
        List<String> chain = new ArrayList<>();
        String cur = path;
        int guard = 0;
        while (guard++ < 4096) {
            chain.add(cur);
            String p = parent.apply(cur);
            if (p == null || p.equals(cur)) break;
            cur = p;
        }
        Collections.reverse(chain);

        List<Crumb> crumbs = new ArrayList<>(chain.size());
        for (int i = 0; i < chain.size(); i++) {
            String node = chain.get(i);
            String label = i == 0 ? node : stripPrefix(node, chain.get(i - 1));
            crumbs.add(new Crumb(label.isEmpty() ? node : label, node));
        }
        return crumbs;
    }

    /** The trailing segment of {@code child} relative to its {@code parent}, with separators trimmed. */
    private static String stripPrefix(String child, String parent) {
        String s = child.startsWith(parent) ? child.substring(parent.length()) : child;
        int start = 0;
        while (start < s.length() && isSep(s.charAt(start))) start++;
        int end = s.length();
        while (end > start && isSep(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isSep(char c) { return c == '/' || c == '\\'; }
}
