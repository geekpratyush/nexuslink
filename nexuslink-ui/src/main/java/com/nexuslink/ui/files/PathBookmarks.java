package com.nexuslink.ui.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordered, path-unique list of directory bookmarks for a file pane ("favourite" folders the user
 * can jump back to). Pure and JavaFX-free so it is unit-testable; persistence is a trivial
 * tab-separated {@code label\tpath} line format, loaded/saved via the static {@link #load}/{@link #save}
 * helpers. Mirrors the pure-helper style of {@link DirectoryDiff} and {@link FileOrder}.
 */
public final class PathBookmarks {

    /** One saved location: a display {@code label} for a directory {@code path}. */
    public record Bookmark(String label, String path) {}

    private final List<Bookmark> bookmarks = new ArrayList<>();

    /**
     * Adds a bookmark, or updates the label if {@code path} is already bookmarked (paths are unique).
     * A blank label defaults to the path's last segment. Returns this for chaining.
     */
    public PathBookmarks add(String label, String path) {
        if (path == null || path.isBlank()) return this;
        String p = path.trim();
        String name = (label == null || label.isBlank()) ? lastSegment(p) : label.trim();
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).path().equals(p)) {
                bookmarks.set(i, new Bookmark(name, p));
                return this;
            }
        }
        bookmarks.add(new Bookmark(name, p));
        return this;
    }

    /** Removes the bookmark for {@code path}, if present. Returns true when one was removed. */
    public boolean remove(String path) {
        return bookmarks.removeIf(b -> b.path().equals(path));
    }

    /** True if {@code path} is already bookmarked. */
    public boolean contains(String path) {
        return bookmarks.stream().anyMatch(b -> b.path().equals(path));
    }

    /** An immutable snapshot in insertion order. */
    public List<Bookmark> list() {
        return List.copyOf(bookmarks);
    }

    public int size() { return bookmarks.size(); }

    /** Serialises to one {@code label\tpath} line per bookmark (tabs/newlines in fields are sanitised). */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Bookmark b : bookmarks) {
            sb.append(clean(b.label())).append('\t').append(clean(b.path())).append('\n');
        }
        return sb.toString();
    }

    /** Parses the {@link #serialize} format; blank and malformed (no-tab) lines are skipped. */
    public static PathBookmarks parse(String text) {
        PathBookmarks out = new PathBookmarks();
        if (text == null) return out;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;                       // no separator → not a bookmark line
            out.add(line.substring(0, tab), line.substring(tab + 1));
        }
        return out;
    }

    /** Loads bookmarks from {@code file}; a missing or unreadable file yields an empty set. */
    public static PathBookmarks load(Path file) {
        try {
            if (file == null || !Files.exists(file)) return new PathBookmarks();
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new PathBookmarks();
        }
    }

    /** Saves bookmarks to {@code file}, creating parent directories as needed. */
    public void save(Path file) throws IOException {
        if (file == null) return;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, serialize(), StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    /** The last path segment (handles trailing separators); falls back to the whole string. */
    private static String lastSegment(String path) {
        String p = path;
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith("\\"))) p = p.substring(0, p.length() - 1);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String seg = slash >= 0 && slash < p.length() - 1 ? p.substring(slash + 1) : p;
        return seg.isBlank() ? p : seg;
    }
}
