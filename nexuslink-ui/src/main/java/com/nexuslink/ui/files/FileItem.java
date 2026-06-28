package com.nexuslink.ui.files;

/**
 * One entry in a {@link FileSystem} listing — local or remote. {@code parent} is true for the
 * synthetic ".." row a browser inserts to navigate up.
 */
public record FileItem(String name, String path, boolean directory, long size,
                       String modified, String permissions, boolean parent) {

    public static FileItem of(String name, String path, boolean directory, long size,
                              String modified, String permissions) {
        return new FileItem(name, path, directory, size, modified, permissions, false);
    }

    /** The synthetic "up one level" row. */
    public static FileItem up(String parentPath) {
        return new FileItem("..", parentPath, true, 0, "", "", true);
    }

    /** A human-readable size (blank for directories). */
    public String sizeText() {
        if (directory) return "";
        if (size < 1024) return size + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double s = size / 1024.0;
        int u = 0;
        while (s >= 1024 && u < units.length - 1) { s /= 1024; u++; }
        return String.format("%.1f %s", s, units[u]);
    }
}
