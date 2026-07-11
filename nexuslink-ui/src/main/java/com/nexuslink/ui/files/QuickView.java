package com.nexuslink.ui.files;

import java.util.Locale;
import java.util.Set;

/**
 * Pure, JavaFX-free classifier deciding how the commander should <em>quick-view</em> a file: as
 * editable text, a read-only image preview, or not at all. The decision is by filename extension plus
 * a size guard, so a huge or binary file is never slurped into an editor. This is the offline-tested
 * seam behind the "Quick view / Edit…" context action; the dialog just renders whatever verdict this
 * returns.
 */
public final class QuickView {

    private QuickView() {}

    /** How a file may be opened in the quick-view dialog. */
    public enum Kind {
        /** Text that can be viewed and edited-in-place (download → edit → upload on save). */
        TEXT,
        /** An image shown read-only. */
        IMAGE,
        /** Too big, a directory, or an unrecognised binary type — no quick view. */
        UNSUPPORTED
    }

    /** Default cap for pulling a file into the editor/preview (4 MB). */
    public static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;

    private static final Set<String> IMAGE_EXTS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp");

    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "log", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "properties", "env", "csv", "tsv", "sql", "sh", "bash", "zsh", "bat", "ps1",
            "java", "kt", "kts", "groovy", "scala", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
            "cc", "cs", "js", "ts", "jsx", "tsx", "html", "htm", "css", "scss", "less", "php",
            "pl", "lua", "r", "gradle", "dockerfile", "gitignore", "makefile", "proto", "graphql", "gql");

    /** A few common extensionless config/text filenames worth treating as text. */
    private static final Set<String> TEXT_NAMES = Set.of(
            "dockerfile", "makefile", "readme", "license", "changelog", ".gitignore", ".env",
            ".bashrc", ".zshrc", ".profile");

    /** Classifies a listing entry, guarding on {@link #DEFAULT_MAX_BYTES}. */
    public static Kind classify(FileItem item) {
        return classify(item, DEFAULT_MAX_BYTES);
    }

    /** Classifies a listing entry, rejecting files larger than {@code maxBytes}. */
    public static Kind classify(FileItem item, long maxBytes) {
        if (item == null || item.parent() || item.directory()) return Kind.UNSUPPORTED;
        if (item.size() > maxBytes) return Kind.UNSUPPORTED;
        String ext = extension(item.name());
        if (IMAGE_EXTS.contains(ext)) return Kind.IMAGE;
        if (TEXT_EXTS.contains(ext)) return Kind.TEXT;
        if (TEXT_NAMES.contains(item.name().toLowerCase(Locale.ROOT))) return Kind.TEXT;
        return Kind.UNSUPPORTED;
    }

    /** True if the file can be opened for editing (text and within the size cap). */
    public static boolean isEditable(FileItem item, long maxBytes) {
        return classify(item, maxBytes) == Kind.TEXT;
    }

    /** The lower-cased extension (without the dot), or "" when there is none. */
    static String extension(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) return ""; // no ext, or a leading-dot dotfile
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
