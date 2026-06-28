package com.nexuslink.protocol.ldap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Parses RFC 2849 LDIF text into {@link LdapEntry} records.
 *
 * <p>Handles folded continuation lines (a leading space joins to the previous line), the base64
 * {@code attr:: value} form, {@code #} comment lines, an optional leading {@code version:} header,
 * and multiple entries separated by blank lines. {@code attr:< url} value-from-URL references are
 * out of scope and are skipped.
 */
public final class LdifReader {

    /** Parse zero or more entries from LDIF text. */
    public List<LdapEntry> read(String ldif) {
        List<LdapEntry> entries = new ArrayList<>();
        LdapEntry current = null;

        for (String logical : unfold(ldif)) {
            if (logical.isEmpty()) {            // blank line: entry separator
                if (current != null) {
                    entries.add(current);
                    current = null;
                }
                continue;
            }
            if (logical.charAt(0) == '#') {     // comment
                continue;
            }

            int colon = logical.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Malformed LDIF line (no ':'): " + logical);
            }
            String name = logical.substring(0, colon);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Malformed LDIF line (empty attribute): " + logical);
            }

            String value;
            int rest = colon + 1;
            if (rest < logical.length() && logical.charAt(rest) == '<') {
                continue;                       // value-from-URL: unsupported, skip
            } else if (rest < logical.length() && logical.charAt(rest) == ':') {
                String b64 = stripLeadingSpaces(logical.substring(rest + 1));
                value = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            } else {
                value = stripLeadingSpaces(logical.substring(rest));
            }

            if (name.equalsIgnoreCase("version") && current == null) {
                continue;                       // version header
            }

            if (name.equalsIgnoreCase("dn")) {
                if (current != null) {          // entries not separated by a blank line
                    entries.add(current);
                }
                current = new LdapEntry(Dn.parse(value));
            } else {
                if (current == null) {
                    throw new IllegalArgumentException("Attribute before dn: " + logical);
                }
                current.add(name, value);
            }
        }

        if (current != null) {
            entries.add(current);
        }
        return entries;
    }

    /** Convenience for input known to hold exactly one entry. */
    public LdapEntry readOne(String ldif) {
        List<LdapEntry> entries = read(ldif);
        if (entries.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one entry, found " + entries.size());
        }
        return entries.get(0);
    }

    /** Remove the RFC 2849 FILL (leading {@code SPACE} characters only) after a value separator. */
    private static String stripLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Merge folded physical lines into logical lines. A line beginning with a single space is a
     * continuation of the previous physical line (the space is removed). Trailing {@code \r} from
     * CRLF input is stripped. Blank lines are preserved as empty logical lines.
     */
    private static List<String> unfold(String ldif) {
        List<String> logical = new ArrayList<>();
        for (String raw : ldif.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (!line.isEmpty() && line.charAt(0) == ' ') {
                if (!logical.isEmpty()) {
                    int last = logical.size() - 1;
                    logical.set(last, logical.get(last) + line.substring(1));
                }
                // a continuation with no preceding line is ignored
            } else {
                logical.add(line);
            }
        }
        return logical;
    }
}
