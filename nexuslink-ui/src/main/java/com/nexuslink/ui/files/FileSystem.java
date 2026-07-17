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

    /**
     * True if this file system can compute a content checksum for a file, letting {@link TransferQueue}
     * verify a transfer by digest rather than by size alone. Defaults to whatever content access allows —
     * a file's bytes can be read back and hashed — so any FS with {@link #supportsContentAccess()} gets
     * checksums for free; {@link LocalFileSystem} overrides {@link #checksum} to stream instead.
     */
    default boolean supportsChecksum() { return supportsContentAccess(); }

    /**
     * The largest file the in-memory {@link #checksum} default can hash — a {@code byte[]} cannot be
     * longer than this, and every {@link #readFile} clamps its request to it.
     */
    long MAX_IN_MEMORY_CHECKSUM_BYTES = Integer.MAX_VALUE;

    /**
     * True if this file system can hash <em>this particular</em> file. Narrower than
     * {@link #supportsChecksum()}: the default {@link #checksum} buffers the whole file into a
     * {@code byte[]}, so anything larger than {@link #MAX_IN_MEMORY_CHECKSUM_BYTES} would come back
     * silently truncated and hash to a digest that wrongly looks like corruption — such a file reports
     * false here instead, and the caller falls back to a size check. An implementation that streams
     * ({@link LocalFileSystem}) or reads a stored digest overrides this to drop the size limit.
     */
    default boolean canChecksum(FileItem item) {
        return supportsChecksum() && !item.directory() && item.size() <= MAX_IN_MEMORY_CHECKSUM_BYTES;
    }

    /**
     * The {@link Checksum#ALGORITHM} digest of {@code item}'s content, as hex. The default reads the whole
     * file into memory via {@link #readFile}; an implementation that can stream (or that already knows a
     * digest, e.g. from object-store metadata) should override, along with {@link #canChecksum}. Callers
     * should consult {@link #canChecksum} first — this throws for a file it cannot faithfully hash rather
     * than returning a digest of a truncated read.
     */
    default String checksum(FileItem item) throws Exception {
        if (item.directory()) {
            throw new UnsupportedOperationException("a directory has no checksum: " + item.name());
        }
        if (!supportsChecksum()) {
            throw new UnsupportedOperationException("checksums not supported by " + name());
        }
        if (item.size() > MAX_IN_MEMORY_CHECKSUM_BYTES) {
            throw new UnsupportedOperationException(
                    name() + " cannot hash a file larger than " + FileItem.humanSize(MAX_IN_MEMORY_CHECKSUM_BYTES)
                            + " in memory: " + item.name());
        }
        return Checksum.sha256(readFile(item, Long.MAX_VALUE));
    }

    /** True if this file system supports changing POSIX permissions (chmod). */
    default boolean supportsChmod() { return false; }

    /** Changes the permission bits of {@code item} (octal, e.g. 0644). Optional. */
    default void chmod(FileItem item, int octalPermissions) throws Exception {
        throw new UnsupportedOperationException("chmod not supported by " + name());
    }
}
