package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link UriTemplate} against the worked examples in
 * <a href="https://www.rfc-editor.org/rfc/rfc6570#section-3.2">RFC 6570 &sect;3.2</a>
 * (Levels 1&ndash;3). The variable bindings below reproduce the canonical example values
 * from RFC 6570 &sect;1.2 / &sect;3.2.1 so each expected expansion can be cited directly
 * from the specification.
 */
class UriTemplateTest {

    /** Canonical RFC 6570 variable set (§3.2.1) plus the "count"/"list"/"keys" example values. */
    private static Map<String, Object> rfcVars() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("count", List.of("one", "two", "three"));
        v.put("dom", List.of("example", "com"));
        v.put("dub", "me/too");
        v.put("hello", "Hello World!");
        v.put("half", "50%");
        v.put("var", "value");
        v.put("who", "fred");
        v.put("base", "http://example.com/home/");
        v.put("path", "/foo/bar");
        v.put("list", List.of("red", "green", "blue"));
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("semi", ";");
        keys.put("dot", ".");
        keys.put("comma", ",");
        v.put("keys", keys);
        v.put("v", "6");
        v.put("x", "1024");
        v.put("y", "768");
        v.put("empty", "");
        v.put("path_reserved", "/foo/bar"); // alias used by {+path} example
        return v;
    }

    private static String expand(String template) {
        return UriTemplate.expand(template, rfcVars());
    }

    // ----- Level 1: simple string expansion (RFC 6570 §1.2, §3.2.2) -----

    @Test
    void level1SimpleStringWithEncoding() {
        // RFC 6570 §3.2.2: {var} -> value ; {hello} percent-encodes space and '!'.
        assertEquals("value", expand("{var}"));
        assertEquals("Hello%20World%21", expand("{hello}"));
        // '%' in "50%" is itself encoded under simple expansion.
        assertEquals("50%25", expand("{half}"));
    }

    @Test
    void level1WithSurroundingLiterals() {
        // RFC 6570 §1.2: literal text is copied verbatim around expansions.
        assertEquals("/value/1024", expand("/{var}/{x}"));
    }

    // ----- Level 2: reserved and fragment expansion (RFC 6570 §3.2.3, §3.2.4) -----

    @Test
    void level2ReservedExpansion() {
        // RFC 6570 §3.2.3: {+var} leaves reserved characters unescaped.
        assertEquals("value", expand("{+var}"));
        // {+path}/here -> /foo/bar/here (slashes preserved).
        assertEquals("/foo/bar/here", expand("{+path}/here"));
        // "Hello World!" under reserved expansion keeps '!' but still encodes the space.
        assertEquals("Hello%20World!", expand("{+hello}"));
    }

    @Test
    void level2ReservedKeepsPctTripleAndReserved() {
        // RFC 6570 §3.2.3: {+base}index -> the '://' and '/' are preserved.
        assertEquals("http://example.com/home/index", expand("{+base}index"));
    }

    @Test
    void level2FragmentExpansion() {
        // RFC 6570 §3.2.4: {#var} prefixes '#' and allows reserved characters.
        assertEquals("#value", expand("{#var}"));
        assertEquals("#/foo/bar", expand("{#path}"));
    }

    // ----- Level 3: multiple variables and operators (RFC 6570 §3.2.x) -----

    @Test
    void level3MultipleVariables() {
        // RFC 6570 §3.2.1: {x,y} -> 1024,768
        assertEquals("1024,768", expand("{x,y}"));
        // {x,hello,y} mixes encoding of the middle value.
        assertEquals("1024,Hello%20World%21,768", expand("{x,hello,y}"));
    }

    @Test
    void level3LabelExpansion() {
        // RFC 6570 §3.2.5: {.who} -> .fred ; {.x,y} -> .1024.768
        assertEquals(".fred", expand("{.who}"));
        assertEquals(".1024.768", expand("{.x,y}"));
    }

    @Test
    void level3PathSegmentExpansionWithExplode() {
        // RFC 6570 §3.2.6: {/list*} explodes to /red/green/blue
        assertEquals("/red/green/blue", expand("{/list*}"));
        // {/var,x}/here -> /value/1024/here
        assertEquals("/value/1024/here", expand("{/var,x}/here"));
    }

    @Test
    void level3PathStyleParametersWithKeys() {
        // RFC 6570 §3.2.7: {;keys} (no explode) -> ;keys=semi,%3B,dot,.,comma,%2C
        assertEquals(";keys=semi,%3B,dot,.,comma,%2C", expand("{;keys}"));
        // {;x,y} -> ;x=1024;y=768
        assertEquals(";x=1024;y=768", expand("{;x,y}"));
        // {;empty} on an empty string -> ;empty (no '=' because ifemp is empty for ';').
        assertEquals(";empty", expand("{;empty}"));
    }

    @Test
    void level3PathStyleParametersExplode() {
        // RFC 6570 §3.2.7: {;keys*} -> ;semi=%3B;dot=.;comma=%2C
        assertEquals(";semi=%3B;dot=.;comma=%2C", expand("{;keys*}"));
    }

    @Test
    void level3FormQueryExpansion() {
        // RFC 6570 §3.2.8: {?x,y} -> ?x=1024&y=768
        assertEquals("?x=1024&y=768", expand("{?x,y}"));
        // {?empty} -> ?empty= (named, empty value yields '=' via ifemp).
        assertEquals("?empty=", expand("{?empty}"));
        // {?list} (no explode) -> ?list=red,green,blue
        assertEquals("?list=red,green,blue", expand("{?list}"));
    }

    @Test
    void level3FormQueryContinuation() {
        // RFC 6570 §3.2.9: {&x} -> &x=1024 ; combined after a query prefix.
        assertEquals("?fixed=yes&x=1024", expand("?fixed=yes{&x}"));
        assertEquals("&x=1024&y=768", expand("{&x,y}"));
    }

    // ----- Modifiers (RFC 6570 §2.4) -----

    @Test
    void prefixModifier() {
        // RFC 6570 §2.4.1 / §3.2.2: {var:3} -> val (first three characters).
        assertEquals("val", expand("{var:3}"));
        // Prefix longer than the value returns the whole value.
        assertEquals("value", expand("{var:30}"));
        // Prefix under form-query keeps the name= wrapping: {?var:3} -> ?var=val
        assertEquals("?var=val", expand("{?var:3}"));
    }

    @Test
    void explodeListForQuery() {
        // RFC 6570 §3.2.8: {?list*} -> ?list=red&list=green&list=blue
        assertEquals("?list=red&list=green&list=blue", expand("{?list*}"));
    }

    @Test
    void explodeMapAssociativeArray() {
        // RFC 6570 §3.2.1: {?keys*} -> ?semi=%3B&dot=.&comma=%2C
        assertEquals("?semi=%3B&dot=.&comma=%2C", expand("{?keys*}"));
        // {keys*} simple explode -> semi=%3B,dot=.,comma=%2C
        assertEquals("semi=%3B,dot=.,comma=%2C", expand("{keys*}"));
    }

    @Test
    void mapWithoutExplodeSimple() {
        // RFC 6570 §3.2.1: {keys} (no explode) flattens to key,value pairs.
        assertEquals("semi,%3B,dot,.,comma,%2C", expand("{keys}"));
    }

    // ----- Undefined-variable handling (RFC 6570 §3.2.1) -----

    @Test
    void undefinedVariablesAreOmitted() {
        Map<String, Object> vars = rfcVars();
        // 'undef' is absent; the separator it would introduce is also dropped.
        assertEquals("1024", UriTemplate.expand("{x,undef}", vars));
        assertEquals("1024", UriTemplate.expand("{undef,x}", vars));
        // A pure-undefined expression collapses to empty (with just literals remaining).
        assertEquals("head-tail", UriTemplate.expand("head-{undef}tail", vars));
        // Query form with all-undefined variables yields nothing (no '?').
        assertEquals("", UriTemplate.expand("{?undef}", vars));
    }

    @Test
    void nullEmptyListAndEmptyMapAreUndefined() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("a", null);
        vars.put("b", new ArrayList<>());
        vars.put("c", new LinkedHashMap<>());
        vars.put("d", "keep");
        assertEquals("keep", UriTemplate.expand("{a,b,c,d}", vars));
    }

    // ----- Malformed templates (parse-time validation) -----

    @Test
    void malformedTemplatesRejected() {
        Map<String, Object> vars = rfcVars();
        // Unbalanced opening brace.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{var", vars));
        // Unbalanced closing brace.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("var}", vars));
        // Empty expression.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{}", vars));
        // Reserved/unknown operator.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{=var}", vars));
        // Illegal character in a variable name.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{ var}", vars));
        // Prefix modifier combined with explode.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{var:3*}", vars));
        // Non-numeric prefix length.
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{var:x}", vars));
        // Null template.
        assertThrows(UriTemplate.UriTemplateException.class, () -> new UriTemplate(null));
    }

    @Test
    void prefixOnCompositeRejectedAtExpandTime() {
        // RFC 6570 §2.4.1: a prefix modifier must not apply to a list/map value.
        Map<String, Object> vars = rfcVars();
        assertThrows(UriTemplate.UriTemplateException.class, () -> UriTemplate.expand("{list:3}", vars));
    }

    @Test
    void instanceApiIsReusable() {
        UriTemplate t = new UriTemplate("{/var,x}/here");
        assertEquals("/value/1024/here", t.expand(rfcVars()));
        assertEquals("{/var,x}/here", t.template());
        // Null variable map is treated as empty bindings.
        assertEquals("/here", t.expand(null));
    }
}
