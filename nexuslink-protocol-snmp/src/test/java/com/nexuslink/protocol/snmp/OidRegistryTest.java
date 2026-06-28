package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests the bundled {@link OidRegistry}: name ↔ OID resolution in both directions, longest-prefix
 * matching with an instance suffix, and the numeric fallback for unknown OIDs. Fully offline.
 */
class OidRegistryTest {

    @Test
    void nameForResolvesBareObjects() {
        assertEquals("sysDescr", OidRegistry.nameFor("1.3.6.1.2.1.1.1"));
        assertEquals("sysUpTime", OidRegistry.nameFor("1.3.6.1.2.1.1.3"));
        assertEquals("sysName", OidRegistry.nameFor("1.3.6.1.2.1.1.5"));
        assertEquals("ifInOctets", OidRegistry.nameFor("1.3.6.1.2.1.2.2.1.10"));
    }

    @Test
    void nameForKeepsTheTrailingInstanceSuffix() {
        assertEquals("sysDescr.0", OidRegistry.nameFor("1.3.6.1.2.1.1.1.0"));
        assertEquals("sysName.0", OidRegistry.nameFor("1.3.6.1.2.1.1.5.0"));
        // Table column.row instance.
        assertEquals("ifDescr.2", OidRegistry.nameFor("1.3.6.1.2.1.2.2.1.2.2"));
        assertEquals("ifInOctets.3", OidRegistry.nameFor("1.3.6.1.2.1.2.2.1.10.3"));
    }

    @Test
    void nameForLeadingDotAccepted() {
        assertEquals("sysDescr.0", OidRegistry.nameFor(".1.3.6.1.2.1.1.1.0"));
    }

    @Test
    void nameForUsesLongestPrefixNotJustAnyPrefix() {
        // 1.3.6.1.2.1.1.1 (sysDescr) must win over the shorter 1.3.6.1.2.1.1 (system) prefix.
        assertEquals("sysDescr.0", OidRegistry.nameFor("1.3.6.1.2.1.1.1.0"));
        // An object under system that we do not name individually falls back to the system branch.
        assertEquals("system.99.0", OidRegistry.nameFor("1.3.6.1.2.1.1.99.0"));
    }

    @Test
    void nameForMatchesOnComponentBoundariesOnly() {
        // sysServices is 1.3.6.1.2.1.1.7; the textual prefix "1.3.6.1.2.1.1.7" must not match "...1.70".
        assertEquals("system.70", OidRegistry.nameFor("1.3.6.1.2.1.1.70"));
    }

    @Test
    void nameForUnknownOidReturnsNumeric() {
        assertEquals("1.2.3.4.5", OidRegistry.nameFor("1.2.3.4.5"));
        assertEquals("1.2.3.4.5", OidRegistry.nameFor(".1.2.3.4.5"));
    }

    @Test
    void nameForNullOrBlankIsNull() {
        assertEquals(null, OidRegistry.nameFor(null));
        assertEquals(null, OidRegistry.nameFor(""));
        assertEquals(null, OidRegistry.nameFor("   "));
    }

    @Test
    void oidForResolvesNamesBothBareAndWithInstance() {
        assertEquals(Optional.of("1.3.6.1.2.1.1.1"), OidRegistry.oidFor("sysDescr"));
        assertEquals(Optional.of("1.3.6.1.2.1.1.1.0"), OidRegistry.oidFor("sysDescr.0"));
        assertEquals(Optional.of("1.3.6.1.2.1.1.5.0"), OidRegistry.oidFor("sysName.0"));
        assertEquals(Optional.of("1.3.6.1.2.1.2.2.1.2.2"), OidRegistry.oidFor("ifDescr.2"));
    }

    @Test
    void oidForTrimsWhitespace() {
        assertEquals(Optional.of("1.3.6.1.2.1.1.5"), OidRegistry.oidFor("  sysName  "));
    }

    @Test
    void oidForUnknownNameIsEmpty() {
        assertEquals(Optional.empty(), OidRegistry.oidFor("noSuchThing"));
        assertEquals(Optional.empty(), OidRegistry.oidFor(""));
        assertEquals(Optional.empty(), OidRegistry.oidFor(null));
    }

    @Test
    void roundTripsNameAndOid() {
        for (String name : OidRegistry.knownNames()) {
            String oid = OidRegistry.oidFor(name).orElseThrow();
            assertEquals(name, OidRegistry.nameFor(oid), "round-trip failed for " + name);
        }
    }

    @Test
    void isKnownDistinguishesNamedFromUnknown() {
        assertTrue(OidRegistry.isKnown("1.3.6.1.2.1.1.1.0"));
        assertTrue(OidRegistry.isKnown("1.3.6.1.2.1.1.1"));
        assertFalse(OidRegistry.isKnown("1.2.3.4.5"));
        assertFalse(OidRegistry.isKnown(null));
    }

    @Test
    void tableIsImmutableSnapshot() {
        assertTrue(OidRegistry.table().containsKey("sysDescr"));
        assertEquals("1.3.6.1.2.1.1.1", OidRegistry.table().get("sysDescr"));
    }
}
