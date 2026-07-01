package com.nexuslink.protocol.ldap;

import com.nexuslink.protocol.ldap.LdapUrl.Extension;
import com.nexuslink.protocol.ldap.LdapUrl.LdapUrlException;
import com.nexuslink.protocol.ldap.LdapUrl.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link LdapUrl}: full five-section parsing, minimal and host-less forms, scheme
 * default ports, percent-encoding of DN/filter, scope/filter defaulting, {@code !critical} extensions,
 * parse→toString→parse round-tripping, and malformed-input rejection (RFC 4516).
 */
class LdapUrlTest {

    @Test
    void parsesAllFiveComponents() {
        LdapUrl u = LdapUrl.parse(
                "ldap://ldap.example.com:1389/ou=People,dc=example,dc=com?cn,mail?sub?(uid=alice)?!x-foo=bar");
        assertFalse(u.isSecure());
        assertEquals("ldap.example.com", u.host());
        assertEquals(1389, u.port());
        assertEquals(Dn.parse("ou=People,dc=example,dc=com"), u.baseDn());
        assertEquals(List.of("cn", "mail"), u.attributes());
        assertEquals(Scope.SUB, u.scope());
        assertEquals("(uid=alice)", u.filter());
        assertEquals(List.of(new Extension(true, "x-foo", "bar")), u.extensions());
    }

    @Test
    void minimalHostlessUrlUsesAllDefaults() {
        LdapUrl u = LdapUrl.parse("ldap:///");
        assertFalse(u.isSecure());
        assertEquals(null, u.host());
        assertEquals(389, u.port());
        assertTrue(u.baseDn().isEmpty());
        assertTrue(u.attributes().isEmpty());
        assertEquals(Scope.BASE, u.scope());
        assertEquals("(objectClass=*)", u.filter());
        assertTrue(u.extensions().isEmpty());
        assertEquals("ldap:///", u.toString());
    }

    @Test
    void bareSchemeWithoutPathIsAccepted() {
        LdapUrl u = LdapUrl.parse("ldap://ldap.example.com");
        assertEquals("ldap.example.com", u.host());
        assertEquals(389, u.port());
        assertTrue(u.baseDn().isEmpty());
        assertEquals("ldap://ldap.example.com", u.toString());
    }

    @Test
    void ldapsDefaultsToPort636() {
        LdapUrl u = LdapUrl.parse("ldaps://secure.example.com/dc=example,dc=com");
        assertTrue(u.isSecure());
        assertEquals("ldaps", u.scheme());
        assertEquals(636, u.port());
        assertTrue(u.isDefaultPort());
    }

    @Test
    void ldapDefaultsToPort389() {
        LdapUrl u = LdapUrl.parse("ldap://host/dc=example");
        assertEquals(389, u.port());
        assertTrue(u.isDefaultPort());
    }

    @Test
    void explicitDefaultPortIsNotEmittedButRoundTrips() {
        LdapUrl u = LdapUrl.parse("ldap://host:389/dc=example");
        assertEquals(389, u.port());
        assertEquals("ldap://host/dc=example", u.toString());
        assertEquals(u, LdapUrl.parse(u.toString()));
    }

    @Test
    void percentDecodesSpacesInDn() {
        LdapUrl u = LdapUrl.parse("ldap://ldap.example.net/o=University%20of%20Michigan,c=US");
        assertEquals(Dn.parse("o=University of Michigan,c=US"), u.baseDn());
    }

    @Test
    void percentDecodesEscapedCommaInDnValue() {
        // RFC 4516 example: value contains a literal comma -> RFC4514 "\2C" -> URL "%5C2C".
        LdapUrl u = LdapUrl.parse("ldap://ldap.example.com/o=An%20Example%5C2C%20Inc.,c=US");
        Dn expected = Dn.parse("o=An Example\\, Inc.,c=US");
        assertEquals(expected, u.baseDn());
        assertEquals(2, u.baseDn().size());
    }

    @Test
    void percentDecodesFilterWithParenthesesAndBackslash() {
        LdapUrl u = LdapUrl.parse("ldap://ldap.example.com/o=Babsco,c=US???(four-octet=%5C00%5C00%5C00%5C04)");
        assertEquals("(four-octet=\\00\\00\\00\\04)", u.filter());
        assertEquals(Scope.BASE, u.scope());
        assertTrue(u.attributes().isEmpty());
    }

    @Test
    void filterWithSpacesAndParensRoundTrips() {
        LdapUrl u = LdapUrl.parse("ldap:///dc=example???(&(cn=John%20Doe)(status=active))");
        assertEquals("(&(cn=John Doe)(status=active))", u.filter());
        assertEquals(u, LdapUrl.parse(u.toString()));
    }

    @Test
    void emptyFilterSectionDefaultsToPresenceFilter() {
        LdapUrl u = LdapUrl.parse("ldap:///dc=example?cn?sub?");
        assertEquals("(objectClass=*)", u.filter());
        assertEquals(Scope.SUB, u.scope());
        assertEquals(List.of("cn"), u.attributes());
    }

    @Test
    void emptyScopeSectionDefaultsToBase() {
        LdapUrl u = LdapUrl.parse("ldap:///dc=example?cn??(uid=x)");
        assertEquals(Scope.BASE, u.scope());
        assertEquals("(uid=x)", u.filter());
    }

    @Test
    void emptyAttributeSectionMeansAllAttributes() {
        LdapUrl u = LdapUrl.parse("ldap:///dc=example??sub");
        assertTrue(u.attributes().isEmpty());
        assertEquals(Scope.SUB, u.scope());
    }

    @Test
    void scopeAcceptsOneAndSub() {
        assertEquals(Scope.ONE, LdapUrl.parse("ldap:///dc=x?cn?one").scope());
        assertEquals(Scope.SUB, LdapUrl.parse("ldap:///dc=x?cn?sub").scope());
        assertEquals(Scope.BASE, LdapUrl.parse("ldap:///dc=x?cn?base").scope());
    }

    @Test
    void criticalAndNonCriticalExtensionsParse() {
        LdapUrl u = LdapUrl.parse("ldap:///????!x-bind=user,x-note=hello");
        assertEquals(List.of(
                new Extension(true, "x-bind", "user"),
                new Extension(false, "x-note", "hello")), u.extensions());
    }

    @Test
    void extensionWithoutValueParses() {
        LdapUrl u = LdapUrl.parse("ldap:///????!x-flag");
        assertEquals(List.of(new Extension(true, "x-flag", null)), u.extensions());
        assertEquals(u, LdapUrl.parse(u.toString()));
    }

    @Test
    void extensionValueWithCommaRoundTrips() {
        LdapUrl u = LdapUrl.of(false, "h", -1, Dn.parse("dc=x"), List.of(), Scope.BASE, null,
                List.of(new Extension(false, "x-list", "a,b,c")));
        assertEquals(u, LdapUrl.parse(u.toString()));
        assertEquals(List.of(new Extension(false, "x-list", "a,b,c")),
                LdapUrl.parse(u.toString()).extensions());
    }

    @Test
    void ipv6HostWithPortParses() {
        LdapUrl u = LdapUrl.parse("ldap://[2001:db8::1]:1389/dc=example");
        assertEquals("[2001:db8::1]", u.host());
        assertEquals(1389, u.port());
        assertEquals(u, LdapUrl.parse(u.toString()));
    }

    @Test
    void ipv6HostWithoutPortUsesDefault() {
        LdapUrl u = LdapUrl.parse("ldaps://[::1]/dc=example");
        assertEquals("[::1]", u.host());
        assertEquals(636, u.port());
    }

    @Test
    void toStringOmitsTrailingDefaultSections() {
        LdapUrl u = LdapUrl.parse("ldap://h/dc=x?cn?base?(objectClass=*)?");
        assertEquals("ldap://h/dc=x?cn", u.toString());
    }

    @Test
    void toStringEncodesSpaceAndDelimiters() {
        LdapUrl u = LdapUrl.of(false, "h", -1, Dn.parse("cn=John Doe,dc=x"), List.of(),
                Scope.SUB, "(cn=a?b)", List.of());
        String s = u.toString();
        assertTrue(s.contains("cn=John%20Doe"), s);
        assertTrue(s.contains("%3F"), s); // '?' inside the filter is escaped
        assertEquals(u, LdapUrl.parse(s));
    }

    @Test
    void fullRoundTripParseFormatParse() {
        String[] samples = {
                "ldap:///",
                "ldap://ldap.example.com",
                "ldaps://secure:636/dc=example,dc=com",
                "ldap://h:1389/ou=People,dc=example,dc=com?cn,sn,mail?sub?(uid=alice)?!x-foo=bar,x-baz=q",
                "ldap://ldap.example.net/o=University%20of%20Michigan,c=US?postalAddress?one",
                "ldap://ldap.example.com/o=An%20Example%5C2C%20Inc.,c=US???(cn=*)",
        };
        for (String s : samples) {
            LdapUrl parsed = LdapUrl.parse(s);
            LdapUrl reparsed = LdapUrl.parse(parsed.toString());
            assertEquals(parsed, reparsed, "round-trip mismatch for " + s + " -> " + parsed);
            assertEquals(parsed.hashCode(), reparsed.hashCode(), "hashCode mismatch for " + s);
        }
    }

    @Test
    void hostAndAttributesAreCaseInsensitiveForEquality() {
        assertEquals(LdapUrl.parse("ldap://Host/dc=x?CN,Mail"),
                LdapUrl.parse("ldap://host/dc=x?cn,mail"));
    }

    @Test
    void schemeIsCaseInsensitive() {
        LdapUrl u = LdapUrl.parse("LDAP://host/dc=x");
        assertFalse(u.isSecure());
        assertEquals("ldap", u.scheme());
    }

    @Test
    void nullUrlThrows() {
        assertThrows(NullPointerException.class, () -> LdapUrl.parse(null));
    }

    @Test
    void wrongSchemeThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("http://host/dc=x"));
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("dc=x"));
    }

    @Test
    void nonNumericPortThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap://host:abc/dc=x"));
    }

    @Test
    void portOutOfRangeThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap://host:99999/dc=x"));
    }

    @Test
    void emptyPortThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap://host:/dc=x"));
    }

    @Test
    void unknownScopeThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap:///dc=x?cn?deep"));
    }

    @Test
    void invalidPercentEncodingThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap:///dc=x?cn??(cn=%ZZ)"));
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap:///o=bad%2"));
    }

    @Test
    void invalidBaseDnThrows() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap:///no-equals-sign"));
    }

    @Test
    void unterminatedIpv6Throws() {
        assertThrows(LdapUrlException.class, () -> LdapUrl.parse("ldap://[::1/dc=x"));
    }

    @Test
    void percentDecodeHandlesUtf8() {
        // "café" -> UTF-8 bytes for é are C3 A9.
        LdapUrl u = LdapUrl.parse("ldap:///cn=caf%C3%A9,dc=x");
        assertEquals(Dn.parse("cn=café,dc=x"), u.baseDn());
        assertEquals(u, LdapUrl.parse(u.toString()));
    }

    @Test
    void ofFactoryAppliesDefaults() {
        LdapUrl u = LdapUrl.of(true, null, -1, null, null, null, null, null);
        assertTrue(u.isSecure());
        assertEquals(636, u.port());
        assertTrue(u.baseDn().isEmpty());
        assertEquals(Scope.BASE, u.scope());
        assertEquals("(objectClass=*)", u.filter());
        assertEquals("ldaps:///", u.toString());
    }
}
