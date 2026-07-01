package com.nexuslink.protocol.ftp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * One structured FTP directory-listing entry, produced by {@link FtpListParser} from either a Unix
 * {@code LIST} line or an RFC 3659 {@code MLSD} line.
 *
 * <p>Fields that do not apply to a given source format are left {@code null} / {@code -1}: a Unix
 * entry has {@link #permissions} and {@link #dateText} but no {@link #modify} or {@link #facts};
 * an MLSD entry has {@link #modify} and {@link #facts} but no {@link #permissions} or
 * {@link #dateText}.
 */
public record FtpListEntry(
        Type type,
        String name,
        long size,
        String permissions,
        String dateText,
        String modify,
        String linkTarget,
        Map<String, String> facts) {

    /** Entry kind. {@code CDIR}/{@code PDIR} are the RFC 3659 "current"/"parent" directory facts. */
    public enum Type { FILE, DIR, SYMLINK, CDIR, PDIR, OTHER }

    private static final DateTimeFormatter MODIFY_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public FtpListEntry {
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(facts);
    }

    /** True for directory-like entries (dir, and the MLSD current/parent directory facts). */
    public boolean isDirectory() {
        return type == Type.DIR || type == Type.CDIR || type == Type.PDIR;
    }

    /** True for a Unix symbolic-link entry ({@code l...}). */
    public boolean isSymlink() {
        return type == Type.SYMLINK;
    }

    /** True when this entry denotes the "." (current) or ".." (parent) directory. */
    public boolean isCurrentOrParent() {
        return type == Type.CDIR || type == Type.PDIR || ".".equals(name) || "..".equals(name);
    }

    /**
     * The MLSD {@code modify} timestamp parsed as a {@link LocalDateTime}, when present and well
     * formed; empty otherwise. The raw string is always available via {@link #modify()}.
     */
    public Optional<LocalDateTime> modifiedDateTime() {
        if (modify == null || modify.length() < 14) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(modify.substring(0, 14), MODIFY_FMT));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
