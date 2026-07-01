package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure path math for the commander's "synchronized browsing" mode: when one pane navigates, the
 * other should make the <em>same relative move</em> (descend into the same sub-folders, or climb the
 * same number of levels). Kept JavaFX-free so it is unit-testable, mirroring {@link FileOrder}.
 *
 * <p>Paths are treated as {@code /}-separated POSIX-style absolute paths (both the local and remote
 * file systems in this app present them that way). The move from {@code sourceOld} to
 * {@code sourceNew} is decomposed into "climb N levels, then descend these segments", and that same
 * move is applied to {@code otherCurrent}. When the two panes share no common prefix the result is
 * the same absolute path as the source (a plain mirror).
 */
public final class SyncBrowsing {

    private SyncBrowsing() {}

    /** Splits a {@code /}-separated path into its non-empty segments. */
    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        for (String s : path.split("/")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String join(List<String> segs) {
        return "/" + String.join("/", segs);
    }

    /**
     * Returns where {@code otherCurrent} should navigate so that it repeats the relative move the
     * source pane just made ({@code sourceOld} &rarr; {@code sourceNew}), or {@code null} if the
     * source path did not actually change.
     */
    public static String mirror(String otherCurrent, String sourceOld, String sourceNew) {
        List<String> oldSegs = segments(sourceOld);
        List<String> newSegs = segments(sourceNew);
        if (oldSegs.equals(newSegs)) return null;

        int common = 0;
        while (common < oldSegs.size() && common < newSegs.size()
                && oldSegs.get(common).equals(newSegs.get(common))) {
            common++;
        }
        int climb = oldSegs.size() - common;                 // levels to go up on the other pane
        List<String> descend = newSegs.subList(common, newSegs.size());

        List<String> other = new ArrayList<>(segments(otherCurrent));
        for (int i = 0; i < climb && !other.isEmpty(); i++) {
            other.remove(other.size() - 1);
        }
        other.addAll(descend);
        return join(other);
    }
}
