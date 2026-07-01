package com.nexuslink.ui.files;

import java.util.Comparator;
import java.util.List;

/**
 * The default ordering for a {@link FileBrowserPane} listing, matching what file commanders like
 * WinSCP / Norton Commander show: the synthetic ".." row first, then directories before files, and
 * within each group a case-insensitive name sort. Kept JavaFX-free so it is unit-testable.
 */
public final class FileOrder {

    /** The column a user can sort a listing by (matches the {@link FileBrowserPane} table columns). */
    public enum SortKey { NAME, SIZE, MODIFIED, PERMISSIONS }

    /**
     * Grouping that a commander always keeps regardless of the sort column: the ".." row first, then
     * directories before files. Only the trailing key (see {@link #by}) ever flips with the sort
     * direction — the grouping is never inverted, matching WinSCP / Norton Commander behaviour.
     */
    private static final Comparator<FileItem> GROUPING =
            Comparator.comparing(FileItem::parent).reversed()              // parent row sorts first
                    .thenComparing(Comparator.comparing(FileItem::directory).reversed());  // dirs before files

    /** Compares two entries by: parent (".." up) first, then directories, then case-insensitive name. */
    public static final Comparator<FileItem> DEFAULT = by(SortKey.NAME, true);

    private FileOrder() {}

    /**
     * A commander-style comparator that keeps ".." first and directories before files, then orders by
     * {@code key} in the requested direction. Only the key part reverses when {@code ascending} is
     * false; the ".."/dirs-first grouping stays put. JavaFX-free, so it is unit-testable.
     */
    public static Comparator<FileItem> by(SortKey key, boolean ascending) {
        Comparator<FileItem> keyed = switch (key) {
            case NAME -> Comparator.comparing(FileItem::name, String.CASE_INSENSITIVE_ORDER);
            case SIZE -> Comparator.comparingLong(FileItem::size);
            case MODIFIED -> Comparator.comparing(FileItem::modified, String.CASE_INSENSITIVE_ORDER);
            case PERMISSIONS -> Comparator.comparing(FileItem::permissions, String.CASE_INSENSITIVE_ORDER);
        };
        if (!ascending) keyed = keyed.reversed();
        // Break ties by name so a size/modified/perms sort stays stable and predictable.
        if (key != SortKey.NAME) keyed = keyed.thenComparing(FileItem::name, String.CASE_INSENSITIVE_ORDER);
        return GROUPING.thenComparing(keyed);
    }

    /** Returns a new list sorted by {@link #DEFAULT}; the input is not modified. */
    public static List<FileItem> sorted(List<FileItem> items) {
        return items.stream().sorted(DEFAULT).toList();
    }

    /** Returns a new list sorted by {@code key}/{@code ascending}; the input is not modified. */
    public static List<FileItem> sorted(List<FileItem> items, SortKey key, boolean ascending) {
        return items.stream().sorted(by(key, ascending)).toList();
    }
}
