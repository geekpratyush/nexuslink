package com.nexuslink.ui.files;

import java.util.List;

/**
 * The in-pane view filter for a {@link FileBrowserPane}: an optional "show hidden" (dotfile) toggle
 * plus a case-insensitive quick-filter substring over the entry name. The synthetic ".." row is
 * always kept so the user can still navigate up while a filter is active. Kept JavaFX-free so it is
 * unit-testable, mirroring {@link FileOrder}.
 */
public final class FileFilter {

    private FileFilter() {}

    /** Whether a single entry survives the current view filter. */
    public static boolean accept(FileItem item, boolean showHidden, String query) {
        if (item.parent()) return true;                                   // ".." is always visible
        if (!showHidden && item.name().startsWith(".")) return false;     // hide dotfiles by default
        if (query == null || query.isBlank()) return true;
        return item.name().toLowerCase().contains(query.trim().toLowerCase());
    }

    /** Returns a new list with only the entries that pass {@link #accept}; input is not modified. */
    public static List<FileItem> apply(List<FileItem> items, boolean showHidden, String query) {
        return items.stream().filter(i -> accept(i, showHidden, query)).toList();
    }
}
