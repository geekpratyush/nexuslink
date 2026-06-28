package com.nexuslink.protocol.ldap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link LdapEntry} records to RFC 2849 LDIF text.
 *
 * <p>Values that are not "safe" (non-ASCII, control characters, or a leading {@code space}/{@code :}
 * /{@code <} or a trailing space) are emitted in the base64 {@code attr:: <base64>} form. Output
 * lines are folded at {@value #MAX_LINE_LENGTH} characters, continuation lines beginning with a
 * single space, so the result round-trips through {@link LdifReader}.
 */
public final class LdifWriter {

    /** Maximum physical line length before folding (RFC 2849 recommends 76). */
    static final int MAX_LINE_LENGTH = 76;

    private final int maxLineLength;

    public LdifWriter() {
        this(MAX_LINE_LENGTH);
    }

    public LdifWriter(int maxLineLength) {
        if (maxLineLength < 2) {
            throw new IllegalArgumentException("maxLineLength must be >= 2");
        }
        this.maxLineLength = maxLineLength;
    }

    /** Serialize a single entry (no trailing blank separator). */
    public String write(LdapEntry entry) {
        StringBuilder sb = new StringBuilder();
        appendEntry(sb, entry);
        return sb.toString();
    }

    /** Serialize several entries, separated by a single blank line. */
    public String write(List<LdapEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            appendEntry(sb, entries.get(i));
        }
        return sb.toString();
    }

    private void appendEntry(StringBuilder sb, LdapEntry entry) {
        appendLine(sb, "dn", entry.dn().toString());
        for (Map.Entry<String, List<String>> attr : entry.attributes().entrySet()) {
            for (String value : attr.getValue()) {
                appendLine(sb, attr.getKey(), value);
            }
        }
    }

    private void appendLine(StringBuilder sb, String name, String value) {
        String logical = isSafe(value)
                ? name + ": " + value
                : name + ":: " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        fold(sb, logical);
        sb.append('\n');
    }

    /** Fold one logical line at {@code maxLineLength}; continuations carry a single leading space. */
    private void fold(StringBuilder sb, String line) {
        if (line.length() <= maxLineLength) {
            sb.append(line);
            return;
        }
        sb.append(line, 0, maxLineLength);
        int i = maxLineLength;
        int chunk = maxLineLength - 1; // leave room for the continuation space
        while (i < line.length()) {
            int end = Math.min(i + chunk, line.length());
            sb.append('\n').append(' ').append(line, i, end);
            i = end;
        }
    }

    /**
     * RFC 2849 SAFE-STRING test: empty is safe; otherwise every char must be {@code 0x01..0x7F}
     * excluding LF/CR, the first char must not be {@code space}/{@code ':'}/{@code '<'}, and the
     * last char must not be a trailing space.
     */
    static boolean isSafe(String value) {
        if (value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || c == '\n' || c == '\r' || c > 0x7F) {
                return false;
            }
            if (i == 0 && (c == ' ' || c == ':' || c == '<')) {
                return false;
            }
        }
        return value.charAt(value.length() - 1) != ' ';
    }
}
