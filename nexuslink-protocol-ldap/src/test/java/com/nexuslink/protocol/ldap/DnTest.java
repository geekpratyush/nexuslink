package com.nexuslink.protocol.ldap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure tests for {@link Dn} / {@link Rdn} parsing, normalization and escaping. */
class DnTest {

    @Test
    void parsesAndCountsRdns() {
        Dn dn = Dn.parse("cn=John Smith,ou=People,dc=example,dc=com");
        assertEquals(4, dn.size());
        assertEquals("cn=John Smith", dn.rdn().toString());
    }

    @Test
    void parentDropsLeafRdn() {
        Dn dn = Dn.parse("cn=John,ou=People,dc=example,dc=com");
        assertEquals(Dn.parse("ou=People,dc=example,dc=com"), dn.parent());
        assertEquals(Dn.parse("dc=com"), Dn.parse("dc=example,dc=com").parent());
    }

    @Test
    void parentOfSingleRdnIsEmpty() {
        assertTrue(Dn.parse("dc=com").parent().isEmpty());
        assertTrue(Dn.EMPTY.parent().isEmpty());
    }

    @Test
    void emptyDnParsing() {
        assertTrue(Dn.parse("").isEmpty());
        assertTrue(Dn.parse("   ").isEmpty());
        assertNull(Dn.EMPTY.rdn());
        assertEquals("", Dn.EMPTY.toString());
    }

    @Test
    void childPrependsLeaf() {
        Dn base = Dn.parse("ou=People,dc=example,dc=com");
        Dn child = base.child(Rdn.of("cn", "Jane"));
        assertEquals(Dn.parse("cn=Jane,ou=People,dc=example,dc=com"), child);
        assertEquals(base, child.parent());
    }

    @Test
    void equalityIgnoresTypeCaseAndInsignificantSpaces() {
        assertEquals(Dn.parse("CN=John,DC=Example,DC=Com"),
                Dn.parse("cn=John, dc=Example , dc=Com"));
        assertEquals(Dn.parse("cn=John,dc=example,dc=com").hashCode(),
                Dn.parse("CN=John,dc=example,dc=com").hashCode());
    }

    @Test
    void equalityIsCaseSensitiveOnValues() {
        assertNotEquals(Dn.parse("cn=john,dc=com"), Dn.parse("cn=JOHN,dc=com"));
    }

    @Test
    void escapedCommaIsNotASeparator() {
        Dn dn = Dn.parse("cn=Smith\\, John,ou=People,dc=com");
        assertEquals(3, dn.size());
        assertEquals("Smith, John", dn.rdn().avas().get(0).value());
    }

    @Test
    void multiValuedRdnParsesAndIsOrderInsensitive() {
        Rdn rdn = Rdn.parse("cn=John+sn=Smith");
        assertTrue(rdn.isMultiValued());
        assertEquals(2, rdn.avas().size());
        assertEquals(Rdn.parse("sn=Smith+cn=John"), rdn);
    }

    @Test
    void escapesSpecialCharactersOnRender() {
        Rdn rdn = Rdn.of("cn", "a+b,c\"d");
        String s = rdn.toString();
        assertEquals("cn=a\\+b\\,c\\\"d", s);
        // round-trips back to the same unescaped value
        assertEquals("a+b,c\"d", Rdn.parse(s).avas().get(0).value());
    }

    @Test
    void escapesLeadingHashAndSurroundingSpaces() {
        assertEquals("cn=\\#tag", Rdn.of("cn", "#tag").toString());
        assertEquals("cn=\\ x\\ ", Rdn.of("cn", " x ").toString());
    }

    @Test
    void leadingAndTrailingSpaceValuesRoundTrip() {
        for (String value : new String[]{" leading", "trailing ", " both ", "mid space"}) {
            Rdn rdn = Rdn.of("cn", value);
            assertEquals(value, Rdn.parse(rdn.toString()).avas().get(0).value(), "value=[" + value + "]");
        }
    }

    @Test
    void hexEscapeIsDecoded() {
        // \2C is a comma
        assertEquals("a,b", Rdn.parse("cn=a\\2Cb").avas().get(0).value());
    }

    @Test
    void descendantCheck() {
        Dn child = Dn.parse("cn=Jane,ou=People,dc=example,dc=com");
        assertTrue(child.isDescendantOf(Dn.parse("ou=People,dc=example,dc=com")));
        assertTrue(child.isDescendantOf(Dn.parse("dc=example,dc=com")));
        assertTrue(child.isDescendantOf(child));
        assertFalse(child.isDescendantOf(Dn.parse("ou=Groups,dc=example,dc=com")));
        assertFalse(Dn.parse("dc=com").isDescendantOf(child));
    }

    @Test
    void rejectsMalformedRdn() {
        assertThrows(IllegalArgumentException.class, () -> Rdn.parse("noequals"));
        assertThrows(IllegalArgumentException.class, () -> Rdn.parse("=value"));
        assertThrows(IllegalArgumentException.class, () -> Dn.parse("cn=a,,dc=com"));
    }
}
