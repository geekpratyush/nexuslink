package com.nexuslink.ui.files;

/**
 * Pure, dependency-free path math for the object-storage commander. It maps the browser's slash-path
 * convention onto a {@code container} + {@code key-prefix} pair, the model every object store shares —
 * an S3/GCS bucket and an Azure Blob container are all "the first path segment":
 * <ul>
 *   <li>{@code "/"} — the root: the list of buckets/containers.</li>
 *   <li>{@code "/my-bucket"} — a bucket's root (empty prefix).</li>
 *   <li>{@code "/my-bucket/a/b/"} — the prefix {@code a/b/} inside {@code my-bucket}.</li>
 * </ul>
 * Object keys use {@code /} as a virtual folder separator; directory prefixes are kept trailing-slashed.
 * This is the offline-tested seam shared by {@code S3FileSystem}, {@code AzureBlobFileSystem} and
 * {@code GcsFileSystem} (which just do I/O).
 */
public final class ObjectPath {

    private ObjectPath() {}

    /** True for the synthetic root path that lists buckets. */
    public static boolean isRoot(String path) {
        return path == null || path.isBlank() || path.equals("/");
    }

    /** The bucket name for {@code path}, or {@code null} at the root. */
    public static String bucket(String path) {
        if (isRoot(path)) return null;
        String p = strip(path);
        int slash = p.indexOf('/');
        return slash < 0 ? p : p.substring(0, slash);
    }

    /**
     * The key prefix within the bucket for {@code path} (no leading slash; trailing slash preserved for a
     * folder). Empty at a bucket root. Null at the commander root.
     */
    public static String prefix(String path) {
        if (isRoot(path)) return null;
        String p = strip(path);
        int slash = p.indexOf('/');
        return slash < 0 ? "" : p.substring(slash + 1);
    }

    /** The parent path ({@code /bucket/a/b/ → /bucket/a/}, {@code /bucket → /}, root stays root). */
    public static String parent(String path) {
        if (isRoot(path)) return "/";
        String p = strip(path);
        int firstSlash = p.indexOf('/');
        if (firstSlash < 0) return "/";                 // /bucket → root
        String noTrailing = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        int lastSlash = noTrailing.lastIndexOf('/');
        if (lastSlash < 0) return "/";                  // shouldn't happen, defensive
        return "/" + noTrailing.substring(0, lastSlash) + "/";
    }

    /**
     * Joins a directory path and a child name. At the root, the child is a bucket ({@code /child}).
     * Inside a bucket, the child is appended under the current prefix. Pass {@code childIsDir} to keep the
     * result trailing-slashed (so it navigates as a folder).
     */
    public static String join(String dir, String name, boolean childIsDir) {
        String cleanName = trimSlashes(name);
        if (isRoot(dir)) {
            return "/" + cleanName;                     // a bucket
        }
        String d = strip(dir);
        if (!d.endsWith("/")) d = d + "/";
        String joined = "/" + d + cleanName;
        return childIsDir ? joined + "/" : joined;
    }

    /**
     * The full object key of a child {@code name} directly under directory {@code dir} (used to build the
     * key for an upload/put). {@code dir} must be inside a bucket.
     */
    public static String childKey(String dir, String name) {
        String prefix = prefix(dir);
        String base = prefix == null ? "" : prefix;
        return base + trimSlashes(name);
    }

    /** The last path segment of a key/prefix, e.g. {@code a/b/c.txt → c.txt}, {@code a/b/ → b}. */
    public static String lastSegment(String keyOrPrefix) {
        if (keyOrPrefix == null || keyOrPrefix.isBlank()) return "";
        String p = keyOrPrefix.endsWith("/") ? keyOrPrefix.substring(0, keyOrPrefix.length() - 1) : keyOrPrefix;
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    private static String strip(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p;
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }
}
