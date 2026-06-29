package com.nexuslink.ui.files;

import java.util.List;

/**
 * A navigable file system backing one pane of the {@link DualPaneBrowser} — either the local disk
 * ({@link LocalFileSystem}) or a remote service (SFTP/FTP). Implementations do the blocking I/O;
 * the browser pane always calls these off the JavaFX thread.
 */
public interface FileSystem {

    /** Short label for the pane header (e.g. "Local", "SFTP"). */
    String name();

    /** The initial directory to show (home / working directory). */
    String home() throws Exception;

    /** The parent directory of {@code path} (returns {@code path} itself when already at the root). */
    String parent(String path);

    /** Joins a directory and a child name into a full path using this file system's separator. */
    String join(String dir, String name);

    /** Lists the entries of {@code path} (directories first, then files; no "." / ".."). */
    List<FileItem> list(String path) throws Exception;

    /**
     * True if an entry named {@code name} already exists in directory {@code dir}. Used by the
     * transfer queue to decide whether to raise an overwrite/skip prompt. The default lists
     * {@code dir} and matches by name; implementations may override with a cheaper check.
     */
    default boolean exists(String dir, String name) throws Exception {
        return list(dir).stream().anyMatch(f -> f.name().equals(name));
    }

    /** Creates a new directory at {@code path}. */
    void mkdir(String path) throws Exception;

    /** Renames/moves {@code from} to {@code to}. */
    void rename(String from, String to) throws Exception;

    /** Deletes a file or directory (recursively for directories). */
    void delete(FileItem item) throws Exception;

    /** True if this file system supports changing POSIX permissions (chmod). */
    default boolean supportsChmod() { return false; }

    /** Changes the permission bits of {@code item} (octal, e.g. 0644). Optional. */
    default void chmod(FileItem item, int octalPermissions) throws Exception {
        throw new UnsupportedOperationException("chmod not supported by " + name());
    }
}
