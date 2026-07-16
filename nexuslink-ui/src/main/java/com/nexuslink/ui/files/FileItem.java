package com.nexuslink.ui.files;

/**
 * One entry in a {@link FileSystem} listing — local or remote. {@code parent} is true for the
 * synthetic ".." row a browser inserts to navigate up.
 */
public record FileItem(String name, String path, boolean directory, long size,
                       String modified, String permissions, boolean parent,
                       long modifiedEpochMillis) {

    public static FileItem of(String name, String path, boolean directory, long size,
                              String modified, String permissions) {
        return new FileItem(name, path, directory, size, modified, permissions, false, 0);
    }

    /**
     * Variant carrying a machine-readable modification time ({@code modifiedEpochMillis}, epoch
     * millis; {@code 0} = unknown) alongside the display string, so "overwrite if newer" can compare
     * timestamps reliably rather than parsing per-file-system date formats.
     */
    public static FileItem of(String name, String path, boolean directory, long size,
                              String modified, String permissions, long modifiedEpochMillis) {
        return new FileItem(name, path, directory, size, modified, permissions, false, modifiedEpochMillis);
    }

    /** The synthetic "up one level" row. */
    public static FileItem up(String parentPath) {
        return new FileItem("..", parentPath, true, 0, "", "", true, 0);
    }

    /** A human-readable size (blank for directories). */
    public String sizeText() {
        return directory ? "" : humanSize(size);
    }

    /** Formats a byte count as a human-readable size (e.g. {@code 1.5 MB}). Pure and reusable. */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double s = bytes / 1024.0;
        int u = 0;
        while (s >= 1024 && u < units.length - 1) { s /= 1024; u++; }
        return String.format("%.1f %s", s, units[u]);
    }
}
