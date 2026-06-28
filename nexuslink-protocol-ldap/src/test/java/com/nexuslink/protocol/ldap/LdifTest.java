package com.nexuslink.protocol.ldap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure (no-server) tests for {@link LdifWriter} / {@link LdifReader}. */
class LdifTest {

    private final LdifWriter writer = new LdifWriter();
    private final LdifReader reader = new LdifReader();

    private static LdapEntry sample() {
        return new LdapEntry("cn=John Smith,ou=People,dc=example,dc=com")
                .add("objectClass", "top", "person", "inetOrgPerson")
                .add("cn", "John Smith")
                .add("sn", "Smith")
                .add("mail", "john@example.com");
    }

    // --- safe-string detection ------------------------------------------------------------------

    @Test
    void safeStringDetection() {
        assertTrue(LdifWriter.isSafe(""));
        assertTrue(LdifWriter.isSafe("john@example.com"));
        assertFalse(LdifWriter.isSafe(" leading"));
        assertFalse(LdifWriter.isSafe(":colon"));
        assertFalse(LdifWriter.isSafe("<less"));
        assertFalse(LdifWriter.isSafe("trailing "));
        assertFalse(LdifWriter.isSafe("Müller"));
        assertFalse(LdifWriter.isSafe("two\nlines"));
        assertTrue(LdifWriter.isSafe("mid:colon ok"));
        assertTrue(LdifWriter.isSafe("a<b ok"));
    }

    // --- writer output --------------------------------------------------------------------------

    @Test
    void writesPlainAndBase64Values() {
        LdapEntry e = new LdapEntry("cn=Test,dc=com")
                .add("cn", "Test")
                .add("description", "Müller");
        String ldif = writer.write(e);
        assertTrue(ldif.contains("dn: cn=Test,dc=com"));
        assertTrue(ldif.contains("cn: Test"));
        String expected = "description:: "
                + Base64.getEncoder().encodeToString("Müller".getBytes(StandardCharsets.UTF_8));
        assertTrue(ldif.contains(expected), ldif);
    }

    @Test
    void foldsLongLinesAtSeventySix() {
        String longValue = "x".repeat(200);
        String ldif = writer.write(new LdapEntry("cn=a,dc=com").add("attr", longValue));
        for (String line : ldif.split("\n")) {
            assertTrue(line.length() <= 76, "line too long (" + line.length() + "): " + line);
        }
        // continuation lines begin with a single space
        String[] lines = ldif.split("\n");
        boolean sawContinuation = false;
        for (String line : lines) {
            if (line.startsWith(" ")) {
                sawContinuation = true;
                assertNotEquals(' ', line.charAt(1));
            }
        }
        assertTrue(sawContinuation);
    }

    // --- round trips ----------------------------------------------------------------------------

    @Test
    void roundTripSingleEntry() {
        LdapEntry original = sample();
        LdapEntry parsed = reader.readOne(writer.write(original));
        assertEquals(original, parsed);
    }

    @Test
    void roundTripMultipleEntries() {
        List<LdapEntry> entries = List.of(
                sample(),
                new LdapEntry("cn=Jane Doe,ou=People,dc=example,dc=com")
                        .add("objectClass", "person")
                        .add("cn", "Jane Doe")
                        .add("sn", "Doe"));
        List<LdapEntry> parsed = reader.read(writer.write(entries));
        assertEquals(entries, parsed);
    }

    @Test
    void roundTripTrickyValues() {
        LdapEntry e = new LdapEntry("cn=Müller,ou=Üsers,dc=example,dc=com")
                .add("cn", "Müller")
                .add("note", " leading space")
                .add("note", "trailing space ")
                .add("note", "has:colon-start", ":startswithcolon")
                .add("multiline", "line one\nline two")
                .add("empty", "")
                .add("bin", "value with <angle and \t tab");
        LdapEntry parsed = reader.readOne(writer.write(e));
        assertEquals(e, parsed);
    }

    @Test
    void roundTripLongFoldedValue() {
        LdapEntry e = new LdapEntry("cn=Long,dc=com")
                .add("description", "The quick brown fox ".repeat(20).trim());
        LdapEntry parsed = reader.readOne(writer.write(e));
        assertEquals(e, parsed);
    }

    // --- reader leniency ------------------------------------------------------------------------

    @Test
    void readsVersionHeaderCommentsAndBlankSeparators() {
        String ldif = """
                version: 1
                # the first entry
                dn: cn=A,dc=com
                cn: A
                # a comment between attributes
                sn: Alpha

                dn: cn=B,dc=com
                cn: B
                """;
        List<LdapEntry> entries = reader.read(ldif);
        assertEquals(2, entries.size());
        assertEquals(List.of("Alpha"), entries.get(0).values("sn"));
        assertEquals("cn=B,dc=com", entries.get(1).dn().toString());
    }

    @Test
    void readsExplicitlyFoldedAndBase64Lines() {
        String b64 = Base64.getEncoder().encodeToString("Café".getBytes(StandardCharsets.UTF_8));
        String ldif = "dn: cn=C,dc=com\n"
                + "descr" + "\n iption:: " + b64 + "\n"   // folded attribute name
                + "cn: C\n";
        LdapEntry e = reader.readOne(ldif);
        assertEquals(List.of("Café"), e.values("description"));
    }

    @Test
    void readsCrlfLineEndings() {
        String ldif = "dn: cn=A,dc=com\r\ncn: A\r\nsn: Alpha\r\n";
        LdapEntry e = reader.readOne(ldif);
        assertEquals(List.of("Alpha"), e.values("sn"));
    }

    @Test
    void entriesWithoutBlankSeparatorStillSplitOnDn() {
        String ldif = "dn: cn=A,dc=com\ncn: A\ndn: cn=B,dc=com\ncn: B\n";
        assertEquals(2, reader.read(ldif).size());
    }

    @Test
    void skipsUrlValuedAttributes() {
        String ldif = "dn: cn=A,dc=com\ncn: A\njpegPhoto:< file:///tmp/x.jpg\n";
        LdapEntry e = reader.readOne(ldif);
        assertTrue(e.values("jpegPhoto").isEmpty());
        assertEquals(List.of("A"), e.values("cn"));
    }

    @Test
    void caseInsensitiveAttributeLookupAndEquality() {
        LdapEntry a = new LdapEntry("cn=A,dc=com").add("ObjectClass", "person");
        LdapEntry b = new LdapEntry("cn=A,dc=com").add("objectclass", "person");
        assertEquals(a, b);
        assertEquals(List.of("person"), a.values("OBJECTCLASS"));
    }

    @Test
    void rejectsAttributeBeforeDn() {
        assertThrows(IllegalArgumentException.class, () -> reader.read("cn: A\ndn: cn=A,dc=com\n"));
    }
}
