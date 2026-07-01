package com.nexuslink.protocol.ldap;

import com.nexuslink.protocol.ldap.LdapFilter.And;
import com.nexuslink.protocol.ldap.LdapFilter.Approx;
import com.nexuslink.protocol.ldap.LdapFilter.Equality;
import com.nexuslink.protocol.ldap.LdapFilter.ExtensibleMatch;
import com.nexuslink.protocol.ldap.LdapFilter.GreaterOrEqual;
import com.nexuslink.protocol.ldap.LdapFilter.LessOrEqual;
import com.nexuslink.protocol.ldap.LdapFilter.Not;
import com.nexuslink.protocol.ldap.LdapFilter.Or;
import com.nexuslink.protocol.ldap.LdapFilter.Present;
import com.nexuslink.protocol.ldap.LdapFilter.Substring;
import com.nexuslink.protocol.ldap.LdapFilterParser.LdapFilterParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link LdapFilterParser} and the {@link LdapFilter} AST: the RFC 4515 §2
 * grammar (simple, presence, substring, boolean combinators, extensible), RFC 4515 §3 escape
 * decoding/re-encoding, parse&nbsp;&rarr;&nbsp;render round-trips, and rejection of malformed input.
 */
class LdapFilterParserTest {

    // --- simple items (RFC 4515 §2) --------------------------------------------------------------

    @Test
    void parsesSimpleEquality() {
        LdapFilter f = LdapFilterParser.parse("(uid=alice)");
        Equality eq = assertInstanceOf(Equality.class, f);
        assertEquals("uid", eq.attribute());
        assertEquals("alice", eq.value());
        assertEquals("(uid=alice)", f.render());
    }

    @Test
    void parsesEmptyEqualityValue() {
        Equality eq = assertInstanceOf(Equality.class, LdapFilterParser.parse("(cn=)"));
        assertEquals("", eq.value());
        assertEquals("(cn=)", eq.render());
    }

    @Test
    void parsesPresence() {
        LdapFilter f = LdapFilterParser.parse("(mail=*)");
        Present p = assertInstanceOf(Present.class, f);
        assertEquals("mail", p.attribute());
        assertEquals("(mail=*)", f.render());
    }

    @Test
    void parsesGreaterLessApprox() {
        assertInstanceOf(GreaterOrEqual.class, LdapFilterParser.parse("(age>=18)"));
        assertInstanceOf(LessOrEqual.class, LdapFilterParser.parse("(age<=65)"));
        assertInstanceOf(Approx.class, LdapFilterParser.parse("(sn~=jansen)"));
        assertEquals("(age>=18)", LdapFilterParser.parse("(age>=18)").render());
        assertEquals("(age<=65)", LdapFilterParser.parse("(age<=65)").render());
        assertEquals("(sn~=jansen)", LdapFilterParser.parse("(sn~=jansen)").render());
    }

    // --- substrings (RFC 4515 §2) ----------------------------------------------------------------

    @Test
    void parsesLeadingSubstring() {
        Substring s = assertInstanceOf(Substring.class, LdapFilterParser.parse("(cn=*smith)"));
        assertEquals("", s.initial());
        assertTrue(s.any().isEmpty());
        assertEquals("smith", s.fin());
        assertEquals("(cn=*smith)", s.render());
    }

    @Test
    void parsesTrailingSubstring() {
        Substring s = assertInstanceOf(Substring.class, LdapFilterParser.parse("(cn=smith*)"));
        assertEquals("smith", s.initial());
        assertTrue(s.any().isEmpty());
        assertEquals("", s.fin());
        assertEquals("(cn=smith*)", s.render());
    }

    @Test
    void parsesContainsSubstring() {
        Substring s = assertInstanceOf(Substring.class, LdapFilterParser.parse("(cn=*smith*)"));
        assertEquals("", s.initial());
        assertEquals(List.of("smith"), s.any());
        assertEquals("", s.fin());
        assertEquals("(cn=*smith*)", s.render());
    }

    @Test
    void parsesMultiStarSubstring() {
        Substring s = assertInstanceOf(Substring.class, LdapFilterParser.parse("(cn=jo*n*doe)"));
        assertEquals("jo", s.initial());
        assertEquals(List.of("n"), s.any());
        assertEquals("doe", s.fin());
        assertEquals("(cn=jo*n*doe)", s.render());
    }

    // --- boolean combinators (RFC 4515 §2) -------------------------------------------------------

    @Test
    void parsesNestedAndOrNot() {
        String filter = "(&(objectClass=person)(|(cn=jd*)(!(uid=guest))))";
        LdapFilter f = LdapFilterParser.parse(filter);
        And and = assertInstanceOf(And.class, f);
        assertEquals(2, and.children().size());
        assertInstanceOf(Equality.class, and.children().get(0));
        Or or = assertInstanceOf(Or.class, and.children().get(1));
        assertInstanceOf(Substring.class, or.children().get(0));
        Not not = assertInstanceOf(Not.class, or.children().get(1));
        assertInstanceOf(Equality.class, not.child());
        assertEquals(filter, f.render());
    }

    @Test
    void toStringMatchesRender() {
        LdapFilter f = LdapFilterParser.parse("(&(a=b)(c=d))");
        assertEquals(f.render(), f.toString());
    }

    // --- RFC 4515 §3 escaping --------------------------------------------------------------------

    @Test
    void decodesEscapedStarInValue() {
        // (cn=a\2ab) — \2a is a literal '*', not a wildcard (RFC 4515 §3).
        Equality eq = assertInstanceOf(Equality.class, LdapFilterParser.parse("(cn=a\\2ab)"));
        assertEquals("a*b", eq.value());
        // Re-escaped on render, equivalent to the input.
        assertEquals("(cn=a\\2ab)", eq.render());
    }

    @Test
    void decodesEscapedParentheses() {
        // RFC 4515 §4 example: (o=Parens R Us \28for all your quoting needs\29)
        String filter = "(o=Parens R Us \\28for all your quoting needs\\29)";
        Equality eq = assertInstanceOf(Equality.class, LdapFilterParser.parse(filter));
        assertEquals("Parens R Us (for all your quoting needs)", eq.value());
        assertEquals(filter, eq.render());
    }

    @Test
    void decodesNulEscape() {
        // (bin=\00) — the NUL byte, escaped per RFC 4515 §3.
        Equality eq = assertInstanceOf(Equality.class, LdapFilterParser.parse("(bin=\\00)"));
        assertEquals("\u0000", eq.value());
        assertEquals("(bin=\\00)", eq.render());
    }

    @Test
    void decodesEscapedBackslash() {
        Equality eq = assertInstanceOf(Equality.class, LdapFilterParser.parse("(cn=a\\5cb)"));
        assertEquals("a\\b", eq.value());
        assertEquals("(cn=a\\5cb)", eq.render());
    }

    @Test
    void acceptsUppercaseHexEscape() {
        // Hex digits are case-insensitive (RFC 4515 §3); render normalises to lowercase.
        Equality eq = assertInstanceOf(Equality.class, LdapFilterParser.parse("(cn=a\\2Ab)"));
        assertEquals("a*b", eq.value());
        assertEquals("(cn=a\\2ab)", eq.render());
    }

    @Test
    void substringSegmentDecodesEscapes() {
        Substring s = assertInstanceOf(Substring.class, LdapFilterParser.parse("(cn=a\\28b*c)"));
        assertEquals("a(b", s.initial());
        assertEquals("c", s.fin());
        assertEquals("(cn=a\\28b*c)", s.render());
    }

    // --- extensible match (RFC 4515 §2) ----------------------------------------------------------

    @Test
    void parsesExtensibleWithRule() {
        ExtensibleMatch m =
                assertInstanceOf(ExtensibleMatch.class, LdapFilterParser.parse("(cn:caseExactMatch:=John)"));
        assertEquals("cn", m.attribute());
        assertEquals("caseExactMatch", m.matchingRule());
        assertEquals("John", m.value());
        assertEquals(false, m.dnAttributes());
        assertEquals("(cn:caseExactMatch:=John)", m.render());
    }

    @Test
    void parsesExtensibleWithDnAndOid() {
        ExtensibleMatch m =
                assertInstanceOf(ExtensibleMatch.class, LdapFilterParser.parse("(cn:dn:2.5.13.5:=John)"));
        assertEquals("cn", m.attribute());
        assertEquals("2.5.13.5", m.matchingRule());
        assertTrue(m.dnAttributes());
        assertEquals("(cn:dn:2.5.13.5:=John)", m.render());
    }

    @Test
    void parsesExtensibleRuleOnly() {
        ExtensibleMatch m =
                assertInstanceOf(ExtensibleMatch.class, LdapFilterParser.parse("(:caseExactMatch:=foo)"));
        assertNull(m.attribute());
        assertEquals("caseExactMatch", m.matchingRule());
        assertEquals("(:caseExactMatch:=foo)", m.render());
    }

    @Test
    void parsesExtensibleAttributeOnly() {
        ExtensibleMatch m =
                assertInstanceOf(ExtensibleMatch.class, LdapFilterParser.parse("(cn:=John)"));
        assertEquals("cn", m.attribute());
        assertNull(m.matchingRule());
        assertEquals(false, m.dnAttributes());
        assertEquals("(cn:=John)", m.render());
    }

    // --- round-trip ------------------------------------------------------------------------------

    @Test
    void roundTripsComplexFilter() {
        String filter = "(&(objectClass=person)(|(sn=*son)(cn=a\\2ab*c))(!(uid=*)))";
        LdapFilter first = LdapFilterParser.parse(filter);
        String rendered = first.render();
        LdapFilter second = LdapFilterParser.parse(rendered);
        // parse -> render -> parse yields an equal tree, and a stable rendering.
        assertEquals(first, second);
        assertEquals(rendered, second.render());
        assertEquals(filter, rendered);
    }

    @Test
    void parsesAttributeWithOptionAndOid() {
        assertEquals("(cn;lang-en=x)", LdapFilterParser.parse("(cn;lang-en=x)").render());
        assertEquals("(2.5.4.3=x)", LdapFilterParser.parse("(2.5.4.3=x)").render());
    }

    // --- malformed input (must throw) ------------------------------------------------------------

    @Test
    void rejectsNullAndEmpty() {
        assertThrows(NullPointerException.class, () -> LdapFilterParser.parse(null));
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse(""));
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("   "));
    }

    @Test
    void rejectsMissingClosingParen() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(cn=alice"));
    }

    @Test
    void rejectsMissingOpeningParen() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("cn=alice)"));
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("cn=alice"));
    }

    @Test
    void rejectsEmptyAttribute() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(=x)"));
    }

    @Test
    void rejectsEmptyItem() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("()"));
    }

    @Test
    void rejectsItemWithoutOperator() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(cn)"));
    }

    @Test
    void rejectsEmptyFilterList() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(&)"));
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(|)"));
    }

    @Test
    void rejectsNotWithMultipleChildren() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(!(a=b)(c=d))"));
    }

    @Test
    void rejectsTrailingCharacters() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(a=b)x"));
    }

    @Test
    void rejectsBadEscape() {
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(cn=a\\zzb)"));
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(cn=a\\5)"));
    }

    @Test
    void rejectsUnescapedReservedCharInOrderingValue() {
        // '(' must be escaped in any assertion value (RFC 4515 §3).
        assertThrows(LdapFilterParseException.class, () -> LdapFilterParser.parse("(age>=1(2)"));
    }
}
