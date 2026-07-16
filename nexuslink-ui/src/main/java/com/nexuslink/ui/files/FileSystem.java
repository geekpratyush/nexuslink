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
     * Free/total capacity of the volume backing {@code path}, for the pane's status line. Empty by
     * default (remote services that can't report it); {@link LocalFileSystem} answers from the disk.
     * Called off the FX thread alongside {@link #list}, so an implementation may do blocking I/O.
     */
    default java.util.Optional<DiskSpace> diskSpace(String path) {
        return java.util.Optional.empty();
    }

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

    /**
     * True if this file system can read/write whole file contents in memory (for the quick-view /
     * edit-in-place dialog). Object stores and plain local/SFTP/FTP support it; a pane hides the
     * quick-view action when this is false.
     */
    default boolean supportsContentAccess() { return false; }

    /** Reads up to {@code maxBytes} of {@code item}'s content. Optional (see {@link #supportsContentAccess()}). */
    default byte[] readFile(FileItem item, long maxBytes) throws Exception {
        throw new UnsupportedOperationException("content access not supported by " + name());
    }

    /** Writes {@code data} to the file named {@code name} in directory {@code dir} (create/overwrite). Optional. */
    default void writeFile(String dir, String name, byte[] data) throws Exception {
        throw new UnsupportedOperationException("content access not supported by " + name());
    }

    /**
     * True if this file system can duplicate an entry in place (the "Duplicate" action). Defaults to
     * whatever content access allows — a file can be duplicated by reading and re-writing its bytes —
     * so any FS with {@link #supportsContentAccess()} gets file duplication for free; {@link
     * LocalFileSystem} overrides to also copy directory trees.
     */
    default boolean supportsCopy() { return supportsContentAccess(); }

    /**
     * Copies file {@code src} to a new entry named {@code destName} in directory {@code destDir}. The
     * default handles a single file via {@link #readFile}/{@link #writeFile}; directory copies require
     * an override (see {@link LocalFileSystem}).
     */
    default void copy(FileItem src, String destDir, String destName) throws Exception {
        if (src.directory()) {
            throw new UnsupportedOperationException(name() + " cannot duplicate a directory");
        }
        writeFile(destDir, destName, readFile(src, Long.MAX_VALUE));
    }

    /** True if this file system supports changing POSIX permissions (chmod). */
    default boolean supportsChmod() { return false; }

    /** Changes the permission bits of {@code item} (octal, e.g. 0644). Optional. */
    default void chmod(FileItem item, int octalPermissions) throws Exception {
        throw new UnsupportedOperationException("chmod not supported by " + name());
    }
}
