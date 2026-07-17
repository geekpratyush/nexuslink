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

    /** How two same-named files are told apart. */
    public enum Match {
        /**
         * Size and modified stamp. Cheap and needs no I/O, but the stamp is the <em>display</em> string:
         * two file systems that format it differently — or a transfer that did not preserve mtime — make
         * byte-identical files read as changed.
         */
        METADATA,
        /**
         * Size, then a content digest for the same-size survivors; stamps are ignored entirely. Immune to
         * the formatting and mtime problems above, at the cost of hashing both copies (see
         * {@link #needsDigest}). Falls back to {@link #METADATA} for any file whose digest is unavailable.
         */
        CONTENT
    }

    /**
     * Supplies a file's content digest, or null when none is available. Kept as a seam so this class does
     * no I/O and stays unit-testable — the caller hashes (off the FX thread) and hands the values in, the
     * same division of labour as {@link TransferIntegrity}.
     */
    @FunctionalInterface
    public interface Digests {
        String of(FileItem item);
        /** No digests known — a {@link Match#CONTENT} compare with this degrades to {@link Match#METADATA}. */
        Digests NONE = item -> null;
    }

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
        return compare(left, right, caseSensitive, Match.METADATA, Digests.NONE);
    }

    /**
     * Compares two listings, telling same-named files apart by {@code match}. For {@link Match#CONTENT},
     * {@code digests} supplies each side's checksum; a typical caller diffs once by metadata, hashes only
     * {@link #needsDigest} entries, then re-compares with those digests.
     */
    public static List<Entry> compare(List<FileItem> left, List<FileItem> right, boolean caseSensitive,
                                      Match match, Digests digests) {
        Map<String, FileItem> l = index(left, caseSensitive);
        Map<String, FileItem> r = index(right, caseSensitive);
        Digests d = digests == null ? Digests.NONE : digests;

        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, FileItem> e : l.entrySet()) {
            FileItem lf = e.getValue();
            FileItem rf = r.get(e.getKey());
            if (rf == null) {
                out.add(new Entry(lf.name(), lf.directory(), Status.LEFT_ONLY, lf, null));
            } else {
                Status s = same(lf, rf, match, d) ? Status.SAME : Status.DIFFERENT;
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
     * The entries a {@link Match#CONTENT} compare actually needs digests for: every same-named file pair
     * of equal size, <em>whatever</em> the metadata verdict was. A differing size differs whatever the
     * bytes say (no hash needed), but an equal size is undecidable from metadata alone — including a pair
     * a stamp-based compare called SAME, since two byte-different files can share a size and a timestamp,
     * and catching exactly that is why a content compare exists. So a caller hashes just these rather than
     * every file in both listings.
     *
     * <p>Pass the result of a {@link Match#METADATA} compare; hash each entry's {@code left}/{@code right}
     * and re-compare with {@link Match#CONTENT}.</p>
     */
    public static List<Entry> needsDigest(List<Entry> metadataDiff) {
        return metadataDiff.stream()
                .filter(e -> (e.status() == Status.DIFFERENT || e.status() == Status.SAME) && !e.directory()
                        && e.left() != null && e.right() != null
                        && e.left().size() == e.right().size())
                .toList();
    }

    /**
     * Whether two same-named entries are considered identical. Two directories always match (a deep
     * compare would need recursion into each subtree, which is out of scope for this single level); a
     * directory versus a file is a type mismatch. Two files must always agree on size; beyond that
     * {@link Match#METADATA} compares the modified stamp while {@link Match#CONTENT} compares digests,
     * falling back to the stamp when either digest is unavailable rather than guessing.
     */
    private static boolean same(FileItem a, FileItem b, Match match, Digests digests) {
        if (a.directory() != b.directory()) return false;   // file vs dir under the same name
        if (a.directory()) return true;                     // both dirs — treated as same at this level
        if (a.size() != b.size()) return false;             // cheap reject, whichever match mode is in play
        if (match == Match.METADATA) return a.modified().equals(b.modified());

        String da = digests.of(a);
        String db = digests.of(b);
        if (!isSet(da) || !isSet(db)) return a.modified().equals(b.modified());
        return da.trim().equalsIgnoreCase(db.trim());       // hex/base64 digests compare case-insensitively
    }

    private static boolean isSet(String s) { return s != null && !s.isBlank(); }

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
