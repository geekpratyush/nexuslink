package com.nexuslink.protocol.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OidTest {

    @Test
    void parseAndRoundTrip() {
        Oid oid = Oid.parse("1.3.6.1.2.1.1.1.0");
        assertEquals("1.3.6.1.2.1.1.1.0", oid.toString());
        assertEquals(9, oid.length());
        assertEquals(1L, oid.get(0));
        assertEquals(0L, oid.get(8));
    }

    @Test
    void parseAcceptsSingleLeadingDot() {
        assertEquals(Oid.parse("1.3.6.1"), Oid.parse(".1.3.6.1"));
        assertEquals("1.3.6.1", Oid.parse(".1.3.6.1").toString());
    }

    @Test
    void parseSingleComponent() {
        Oid oid = Oid.parse("0");
        assertEquals(1, oid.length());
        assertEquals(0L, oid.get(0));
        assertEquals("0", oid.toString());
    }

    @Test
    void parseAllowsLargeUnsignedSubId() {
        Oid oid = Oid.parse("1.4294967295");
        assertEquals(Oid.MAX_SUB_ID, oid.get(1));
        assertEquals("1.4294967295", oid.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", "1..2", "1.-1.2", "a.b", "1.3.", ".", "1.4294967296", "1.x"})
    void parseRejectsMalformed(String bad) {
        assertThrows(OidFormatException.class, () -> Oid.parse(bad));
    }

    @Test
    void parseRejectsNull() {
        assertThrows(OidFormatException.class, () -> Oid.parse(null));
    }

    @Test
    void malformedExceptionCarriesInput() {
        OidFormatException ex = assertThrows(OidFormatException.class, () -> Oid.parse("1..2"));
        assertEquals("1..2", ex.input());
    }

    @Test
    void ofBuildsFromSubIds() {
        assertEquals(Oid.parse("1.3.6"), Oid.of(1, 3, 6));
        assertThrows(OidFormatException.class, Oid::of);
        assertThrows(OidFormatException.class, () -> Oid.of(1, -1));
        assertThrows(OidFormatException.class, () -> Oid.of(1, Oid.MAX_SUB_ID + 1));
    }

    @Test
    void comparatorOrdersByLexicographicRules() {
        Oid a = Oid.parse("1.3.6.1.2.1.1.1");
        Oid b = Oid.parse("1.3.6.1.2.1.1.1.0");
        Oid c = Oid.parse("1.3.6.1.2.1.1.2");
        assertTrue(a.compareTo(b) < 0, "prefix sorts before longer");
        assertTrue(b.compareTo(c) < 0, "differing component decides over length");
        assertTrue(a.compareTo(c) < 0);
        assertEquals(0, a.compareTo(Oid.parse("1.3.6.1.2.1.1.1")));
    }

    @Test
    void comparatorSortsShuffledList() {
        List<Oid> expected = new ArrayList<>(List.of(
                Oid.parse("1.3.6.1.2.1.1.1"),
                Oid.parse("1.3.6.1.2.1.1.1.0"),
                Oid.parse("1.3.6.1.2.1.1.2"),
                Oid.parse("1.3.6.1.2.1.1.2.0"),
                Oid.parse("1.3.6.1.2.1.2"),
                Oid.parse("1.3.6.1.2.1.10")));
        List<Oid> shuffled = new ArrayList<>(expected);
        Collections.shuffle(shuffled, new java.util.Random(42));
        Collections.sort(shuffled);
        assertEquals(expected, shuffled);
    }

    @Test
    void numericOrderNotStringOrder() {
        // Lexicographic by *number*: 2 < 10, whereas string compare would put "10" before "2".
        assertTrue(Oid.parse("1.2").compareTo(Oid.parse("1.10")) < 0);
    }

    @Test
    void isPrefixOfAndStartsWith() {
        Oid subtree = Oid.parse("1.3.6.1.2.1.1");
        Oid inside = Oid.parse("1.3.6.1.2.1.1.5.0");
        Oid outside = Oid.parse("1.3.6.1.2.1.2");

        assertTrue(subtree.isPrefixOf(inside));
        assertTrue(inside.startsWith(subtree));
        assertTrue(subtree.isPrefixOf(subtree), "an OID is a prefix of itself");

        assertFalse(subtree.isPrefixOf(outside));
        assertFalse(outside.startsWith(subtree));
        // A longer OID cannot be a prefix of a shorter one.
        assertFalse(inside.isPrefixOf(subtree));
    }

    @Test
    void childParentSubBehavior() {
        Oid base = Oid.parse("1.3.6");
        assertEquals(Oid.parse("1.3.6.1.0"), base.child(1, 0));
        assertEquals(base, base.child(), "child with no args returns same value");

        assertEquals(Oid.parse("1.3"), base.parent());
        assertEquals(base, base.child(9).parent());

        assertEquals(Oid.parse("3.6"), base.sub(1, 3));
        assertEquals(base, base.sub(0, 3));
    }

    @Test
    void parentOfSingleComponentThrows() {
        assertThrows(OidFormatException.class, () -> Oid.parse("1").parent());
    }

    @Test
    void childRejectsOutOfRange() {
        Oid base = Oid.parse("1.3.6");
        assertThrows(OidFormatException.class, () -> base.child(-1));
        assertThrows(OidFormatException.class, () -> base.child(Oid.MAX_SUB_ID + 1));
    }

    @Test
    void subRejectsInvalidRange() {
        Oid base = Oid.parse("1.3.6");
        assertThrows(IndexOutOfBoundsException.class, () -> base.sub(-1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> base.sub(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> base.sub(2, 1));
        assertThrows(OidFormatException.class, () -> base.sub(1, 1), "empty range is not a valid OID");
    }

    @Test
    void nextIsAppendZeroAndStrictlyGreater() {
        Oid oid = Oid.parse("1.3.6.1.2.1.1.1");
        Oid next = oid.next();
        assertEquals(Oid.parse("1.3.6.1.2.1.1.1.0"), next);
        assertTrue(oid.compareTo(next) < 0);
        assertTrue(oid.isPrefixOf(next), "successor stays within the node's subtree");
    }

    @Test
    void getOutOfBoundsThrows() {
        Oid oid = Oid.parse("1.3.6");
        assertThrows(IndexOutOfBoundsException.class, () -> oid.get(3));
        assertThrows(IndexOutOfBoundsException.class, () -> oid.get(-1));
    }

    @Test
    void toListIsImmutableSnapshot() {
        Oid oid = Oid.parse("1.3.6");
        List<Long> list = oid.toList();
        assertEquals(List.of(1L, 3L, 6L), list);
        assertThrows(UnsupportedOperationException.class, () -> list.add(9L));
    }

    @Test
    void equalsAndHashCode() {
        Oid a = Oid.parse("1.3.6.1");
        Oid b = Oid.parse(".1.3.6.1");
        Oid c = Oid.parse("1.3.6.2");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(null, a);
    }
}
