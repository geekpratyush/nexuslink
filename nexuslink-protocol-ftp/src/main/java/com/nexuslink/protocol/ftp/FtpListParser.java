package com.nexuslink.protocol.ftp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free parser for the two directory-listing formats an FTP server returns: the
 * traditional Unix {@code LIST} long format and the machine-oriented RFC 3659 {@code MLSD} format.
 * It performs string parsing only (no network, no Apache Commons Net) so it is fully offline
 * testable.
 *
 * <p><b>Contract:</b> the single-line parsers ({@link #parseUnix}, {@link #parseMlsd},
 * {@link #parseLine}) return an empty {@link Optional} for a line they cannot parse rather than
 * throwing. The bulk parsers ({@link #parse(List)}, {@link #parse(String)}) skip unparseable lines,
 * blank lines, and the "." / ".." current/parent-directory entries.
 */
public final class FtpListParser {

    private FtpListParser() {}

    /**
     * Unix long listing, e.g. {@code -rw-r--r--   1 owner group    4096 Sep 12 14:22 file.txt}.
     * Columns: type+permissions, link count, owner, group, size, month, day, time-or-year, name.
     */
    private static final Pattern UNIX = Pattern.compile(
            "^([-dlbcpsDLBCPS])([-rwxsStTlL]{9})\\s+"   // 1 type char, 2 permission bits
            + "(\\d+)\\s+"                              // 3 link count
            + "(\\S+)\\s+"                              // 4 owner
            + "(\\S+)\\s+"                              // 5 group
            + "(\\d+)\\s+"                              // 6 size
            + "([A-Za-z]{3})\\s+(\\d{1,2})\\s+(\\d{1,2}:\\d{2}|\\d{4})\\s+"  // 7 mon 8 day 9 time/year
            + "(.+)$");                                 // 10 name (may contain spaces)

    private static final String SYMLINK_ARROW = " -> ";

    /** Parses a single Unix {@code LIST} line; empty if it is not a well-formed Unix listing line. */
    public static Optional<FtpListEntry> parseUnix(String line) {
        if (line == null) return Optional.empty();
        String trimmed = stripEol(line);
        Matcher m = UNIX.matcher(trimmed);
        if (!m.matches()) return Optional.empty();

        char typeChar = m.group(1).charAt(0);
        String permissions = m.group(1) + m.group(2);
        long size;
        try {
            size = Long.parseLong(m.group(6));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String dateText = m.group(7) + " " + m.group(8) + " " + m.group(9);
        String rawName = m.group(10).trim();

        FtpListEntry.Type type = unixType(typeChar);
        String name = rawName;
        String linkTarget = null;
        if (type == FtpListEntry.Type.SYMLINK) {
            int arrow = rawName.indexOf(SYMLINK_ARROW);
            if (arrow >= 0) {
                name = rawName.substring(0, arrow).trim();
                linkTarget = rawName.substring(arrow + SYMLINK_ARROW.length()).trim();
            }
        }
        return Optional.of(new FtpListEntry(type, name, size, permissions, dateText, null, linkTarget, null));
    }

    /**
     * Parses a single RFC 3659 {@code MLSD} line, e.g.
     * {@code type=file;size=4096;modify=20230912142200; file.txt}. Fact names are matched
     * case-insensitively. Empty if the line has no fact/pathname structure.
     */
    public static Optional<FtpListEntry> parseMlsd(String line) {
        if (line == null) return Optional.empty();
        String trimmed = stripEol(line);
        if (trimmed.isEmpty()) return Optional.empty();

        int space = trimmed.indexOf(' ');
        if (space < 0) return Optional.empty();
        String factsPart = trimmed.substring(0, space);
        String name = trimmed.substring(space + 1);
        if (name.isEmpty()) return Optional.empty();
        // A valid facts block is a semicolon-terminated list of "name=value" pairs.
        if (factsPart.indexOf('=') < 0) return Optional.empty();

        Map<String, String> facts = new LinkedHashMap<>();
        for (String pair : factsPart.split(";")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            facts.put(pair.substring(0, eq).toLowerCase(), pair.substring(eq + 1));
        }
        if (facts.isEmpty()) return Optional.empty();

        FtpListEntry.Type type = mlsdType(facts.get("type"));
        long size = -1;
        String sizeFact = facts.get("size");
        if (sizeFact != null) {
            try {
                size = Long.parseLong(sizeFact.trim());
            } catch (NumberFormatException ignored) {
                size = -1;
            }
        }
        String modify = facts.get("modify");
        return Optional.of(new FtpListEntry(type, name, size, null, null, modify, null, facts));
    }

    /**
     * Auto-detects the format of a single line and parses it. Unix long-listing lines are tried
     * first; anything else is attempted as MLSD. Empty if neither format matches.
     */
    public static Optional<FtpListEntry> parseLine(String line) {
        Optional<FtpListEntry> unix = parseUnix(line);
        if (unix.isPresent()) return unix;
        return parseMlsd(line);
    }

    /** Parses many lines, skipping blank/unparseable lines and "." / ".." entries. */
    public static List<FtpListEntry> parse(List<String> lines) {
        List<FtpListEntry> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (line == null || stripEol(line).isBlank()) continue;
            Optional<FtpListEntry> entry = parseLine(line);
            if (entry.isEmpty()) continue;
            if (entry.get().isCurrentOrParent()) continue;
            out.add(entry.get());
        }
        return out;
    }

    /** Parses a multi-line listing (splitting on CR/LF); same skipping rules as {@link #parse(List)}. */
    public static List<FtpListEntry> parse(String multiline) {
        if (multiline == null || multiline.isEmpty()) return new ArrayList<>();
        return parse(List.of(multiline.split("\\r?\\n", -1)));
    }

    private static FtpListEntry.Type unixType(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'd' -> FtpListEntry.Type.DIR;
            case 'l' -> FtpListEntry.Type.SYMLINK;
            case '-' -> FtpListEntry.Type.FILE;
            default -> FtpListEntry.Type.OTHER;
        };
    }

    private static FtpListEntry.Type mlsdType(String value) {
        if (value == null) return FtpListEntry.Type.OTHER;
        return switch (value.toLowerCase()) {
            case "file" -> FtpListEntry.Type.FILE;
            case "dir" -> FtpListEntry.Type.DIR;
            case "cdir" -> FtpListEntry.Type.CDIR;
            case "pdir" -> FtpListEntry.Type.PDIR;
            default -> FtpListEntry.Type.OTHER;
        };
    }

    private static String stripEol(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\r' || c == '\n') end--;
            else break;
        }
        return s.substring(0, end);
    }
}
