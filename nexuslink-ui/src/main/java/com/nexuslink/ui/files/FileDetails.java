package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the label/value rows shown in a file's Properties dialog from a {@link FileItem}, plus the
 * pure symbolic→octal permission conversion behind them. JavaFX-free so it is unit-testable; the
 * dialog is just a renderer over {@link #of}. Mirrors the pure-helper style of {@link FileItem} and
 * {@link DirectoryDiff}.
 */
public final class FileDetails {

    /** One label/value row in the properties view. */
    public record Row(String label, String value) {}

    private FileDetails() {}

    /** The ordered rows describing {@code item}: name, type, path, size, modified, permissions. */
    public static List<Row> of(FileItem item) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Name", item.name()));
        rows.add(new Row("Type", item.directory() ? "Directory" : "File"));
        rows.add(new Row("Path", item.path()));
        if (!item.directory()) {
            rows.add(new Row("Size", item.size() + " bytes (" + FileItem.humanSize(item.size()) + ")"));
        }
        if (item.modified() != null && !item.modified().isBlank()) {
            rows.add(new Row("Modified", item.modified()));
        }
        if (item.permissions() != null && !item.permissions().isBlank()) {
            String perms = item.permissions();
            String octal = permissionsOctal(perms);
            rows.add(new Row("Permissions", octal == null ? perms : perms + "  (" + octal + ")"));
        }
        return rows;
    }

    /**
     * Converts a 9- or 10-character symbolic permission string ({@code rwxr-xr-x} or {@code -rwxr-xr-x})
     * to its 3-digit octal form ({@code 755}), or {@code null} when the string is not a recognisable
     * POSIX permission triples. A leading type character (—/d/l/…) is ignored; {@code s}/{@code t}
     * setuid/sticky bits are treated as their executable slot.
     */
    public static String permissionsOctal(String symbolic) {
        if (symbolic == null) return null;
        String s = symbolic.length() == 10 ? symbolic.substring(1) : symbolic;
        if (s.length() != 9) return null;
        int octal = 0;
        for (int group = 0; group < 3; group++) {
            int base = group * 3;
            char r = s.charAt(base), w = s.charAt(base + 1), x = s.charAt(base + 2);
            if ((r != 'r' && r != '-') || (w != 'w' && w != '-') || "xstST-".indexOf(x) < 0) {
                return null;   // not a POSIX permission triple
            }
            int bits = 0;
            if (r == 'r') bits |= 4;
            if (w == 'w') bits |= 2;
            // Execute slot: x or s/t (setuid/setgid/sticky) set the bit; S/T and - leave it clear.
            if (x == 'x' || x == 's' || x == 't') bits |= 1;
            octal = octal * 10 + bits;
        }
        return String.format("%03d", octal);
    }
}
