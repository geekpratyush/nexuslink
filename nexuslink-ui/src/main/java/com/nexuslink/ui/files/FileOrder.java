package com.nexuslink.ui.files;

import java.util.Comparator;
import java.util.List;

/**
 * The default ordering for a {@link FileBrowserPane} listing, matching what file commanders like
 * WinSCP / Norton Commander show: the synthetic ".." row first, then directories before files, and
 * within each group a case-insensitive name sort. Kept JavaFX-free so it is unit-testable.
 */
public final class FileOrder {

    /** Compares two entries by: parent (".." up) first, then directories, then case-insensitive name. */
    public static final Comparator<FileItem> DEFAULT =
            Comparator.comparing(FileItem::parent).reversed()              // parent row sorts first
                    .thenComparing(Comparator.comparing(FileItem::directory).reversed())  // dirs before files
                    .thenComparing(f -> f.name(), String.CASE_INSENSITIVE_ORDER);

    private FileOrder() {}

    /** Returns a new list sorted by {@link #DEFAULT}; the input is not modified. */
    public static List<FileItem> sorted(List<FileItem> items) {
        return items.stream().sorted(DEFAULT).toList();
    }
}
