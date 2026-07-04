package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single-level comparison of two directory listings — the "left" (e.g. local) pane against the
 * "right" (e.g. remote) pane. Every entry is matched by name and classified as present on only one
 * side, present on both but differing, or identical, which is what a commander needs to highlight
 * new / changed / missing files and what {@code SyncService} (7.1) builds its plan from. The
 * synthetic ".." row is ignored. Kept JavaFX-free so it is unit-testable, mirroring {@link FileOrder}
 * and {@link FileFilter}.
 */
public final class DirectoryDiff {

    /** How a matched name relates across the two listings. */
    public enum Status {
        /** Present on the left only (new locally / missing remotely). */
        LEFT_ONLY,
        /** Present on the right only (missing locally / new remotely). */
        RIGHT_ONLY,
        /** Present on both sides but the size, timestamp, or file/dir type differs. */
        DIFFERENT,
        /** Present on both sides and identical by the compared attributes. */
        SAME
    }

    /**
     * One reconciled row. Exactly one of {@code left}/{@code right} is null for LEFT_ONLY/RIGHT_ONLY;
     * both are non-null for DIFFERENT/SAME. {@code directory} reflects whichever side is present.
     */
    public record Entry(String name, boolean directory, Status status, FileItem left, FileItem right) {}

    private DirectoryDiff() {}

    /** Compares two listings with case-sensitive name matching (POSIX-safe default). */
    public static List<Entry> compare(List<FileItem> left, List<FileItem> right) {
        return compare(left, right, true);
    }

    /**
     * Compares two listings, matching entries by name (optionally case-insensitively for
     * Windows-style filesystems). Neither input is modified. The result is ordered directories-first
     * then case-insensitive name, matching how a commander lists a merged view.
     */
    public static List<Entry> compare(List<FileItem> left, List<FileItem> right, boolean caseSensitive) {
        Map<String, FileItem> l = index(left, caseSensitive);
        Map<String, FileItem> r = index(right, caseSensitive);

        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, FileItem> e : l.entrySet()) {
            FileItem lf = e.getValue();
            FileItem rf = r.get(e.getKey());
            if (rf == null) {
                out.add(new Entry(lf.name(), lf.directory(), Status.LEFT_ONLY, lf, null));
            } else {
                Status s = same(lf, rf) ? Status.SAME : Status.DIFFERENT;
                out.add(new Entry(lf.name(), lf.directory(), s, lf, rf));
            }
        }
        for (Map.Entry<String, FileItem> e : r.entrySet()) {
            if (!l.containsKey(e.getKey())) {
                FileItem rf = e.getValue();
                out.add(new Entry(rf.name(), rf.directory(), Status.RIGHT_ONLY, null, rf));
            }
        }
        out.sort(Comparator.comparing(Entry::directory).reversed()          // dirs before files
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** A count of each {@link Status} in a diff — handy for a "N new · M changed · K missing" summary. */
    public static Map<Status, Integer> summary(List<Entry> diff) {
        Map<Status, Integer> counts = new LinkedHashMap<>();
        for (Status s : Status.values()) counts.put(s, 0);
        for (Entry e : diff) counts.merge(e.status(), 1, Integer::sum);
        return counts;
    }

    /**
     * Whether two same-named entries are considered identical. Two directories always match (a deep
     * compare would need recursion into each subtree, which is out of scope for this single level); a
     * directory versus a file is a type mismatch; two files match when their size and modified stamp
     * are equal.
     */
    private static boolean same(FileItem a, FileItem b) {
        if (a.directory() != b.directory()) return false;   // file vs dir under the same name
        if (a.directory()) return true;                     // both dirs — treated as same at this level
        return a.size() == b.size() && a.modified().equals(b.modified());
    }

    /** Indexes a listing by name (dropping the synthetic ".." row), preserving first-seen order. */
    private static Map<String, FileItem> index(List<FileItem> items, boolean caseSensitive) {
        Map<String, FileItem> map = new LinkedHashMap<>();
        for (FileItem i : items) {
            if (i.parent()) continue;
            map.putIfAbsent(caseSensitive ? i.name() : i.name().toLowerCase(), i);
        }
        return map;
    }
}
