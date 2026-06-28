package com.nexuslink.protocol.ldap;

import com.nexuslink.protocol.ldap.LdapFilterBuilder.Condition;
import com.nexuslink.protocol.ldap.LdapFilterBuilder.Operator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for {@link LdapFilterBuilder}: per-operator rendering, AND/OR joining, blank-row
 * skipping and the empty default, plus RFC 4515 escaping of {@code * ( ) \ NUL}.
 */
class LdapFilterBuilderTest {

    @Test
    void equalityRendersPlainAssertion() {
        assertEquals("(uid=alice)",
                LdapFilterBuilder.build(List.of(Condition.of("uid", Operator.EQUALS, "alice")), true));
    }

    @Test
    void containsWrapsValueWithWildcards() {
        assertEquals("(cn=*smith*)",
                LdapFilterBuilder.render(Condition.of("cn", Operator.CONTAINS, "smith")));
    }

    @Test
    void startsWithAppendsTrailingWildcard() {
        assertEquals("(cn=smith*)",
                LdapFilterBuilder.render(Condition.of("cn", Operator.STARTS_WITH, "smith")));
    }

    @Test
    void presentIgnoresValue() {
        assertEquals("(mail=*)",
                LdapFilterBuilder.render(Condition.of("mail", Operator.PRESENT, "ignored")));
    }

    @Test
    void orderingOperatorsRender() {
        assertEquals("(uidNumber>=1000)",
                LdapFilterBuilder.render(Condition.of("uidNumber", Operator.GREATER_OR_EQUAL, "1000")));
        assertEquals("(uidNumber<=2000)",
                LdapFilterBuilder.render(Condition.of("uidNumber", Operator.LESS_OR_EQUAL, "2000")));
    }

    @Test
    void andJoinsMultipleConditions() {
        String f = LdapFilterBuilder.build(List.of(
                Condition.of("objectClass", Operator.EQUALS, "person"),
                Condition.of("sn", Operator.STARTS_WITH, "Ad")), true);
        assertEquals("(&(objectClass=person)(sn=Ad*))", f);
    }

    @Test
    void orJoinsMultipleConditions() {
        String f = LdapFilterBuilder.build(List.of(
                Condition.of("uid", Operator.EQUALS, "alice"),
                Condition.of("uid", Operator.EQUALS, "bob")), false);
        assertEquals("(|(uid=alice)(uid=bob))", f);
    }

    @Test
    void specialCharactersAreEscaped() {
        // value containing * ( ) \ NUL must each become \HH and never act as wildcards
        assertEquals("(cn=a\\2ab\\28c\\29d\\5ce\\00f)",
                LdapFilterBuilder.render(Condition.of("cn", Operator.EQUALS, "a*b(c)d\\e\0f")));
        // a literal asterisk inside a contains value stays escaped, framing wildcards stay literal
        assertEquals("(cn=*x\\2ay*)",
                LdapFilterBuilder.render(Condition.of("cn", Operator.CONTAINS, "x*y")));
    }

    @Test
    void blankRowsSkippedAndEmptyYieldsDefault() {
        assertEquals("(objectClass=*)", LdapFilterBuilder.build(List.of(), true));
        // a blank-attribute row collapses to the single real condition (no & wrapper)
        String f = LdapFilterBuilder.build(List.of(
                Condition.of("", Operator.EQUALS, "x"),
                Condition.of("uid", Operator.EQUALS, "bob")), true);
        assertEquals("(uid=bob)", f);
    }
}
