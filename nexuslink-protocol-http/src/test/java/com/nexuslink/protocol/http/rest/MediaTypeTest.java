package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MediaType} against RFC 7231 §3.1.1.1: {@code type/subtype} parsing, quoted and
 * token parameters, case-insensitivity, wildcard {@code matches}, malformed-input rejection and
 * parse -&gt; toString -&gt; parse round-tripping.
 */
class MediaTypeTest {

    @Test
    void parsesBareType() {
        MediaType mt = MediaType.parse("application/json");
        assertEquals("application", mt.type());
        assertEquals("json", mt.subtype());
        assertEquals("application/json", mt.essence());
        assertTrue(mt.parameters().isEmpty());
        assertTrue(mt.charset().isEmpty());
        assertFalse(mt.isMultipart());
        assertFalse(mt.isText());
    }

    @Test
    void lowerCasesTypeAndSubtypeCaseInsensitively() {
        MediaType mt = MediaType.parse("Application/JSON");
        assertEquals("application", mt.type());
        assertEquals("json", mt.subtype());
        assertEquals(MediaType.parse("application/json"), mt);
    }

    @Test
    void extractsCharsetCaseInsensitivelyForNameAndPreservesValue() {
        MediaType mt = MediaType.parse("text/html; charset=UTF-8");
        assertTrue(mt.isText());
        assertEquals("UTF-8", mt.charset().orElseThrow());
        // Parameter name lookup is case-insensitive.
        assertEquals("UTF-8", mt.parameter("CharSet"));
        // Value casing is preserved verbatim.
        assertEquals("UTF-8", mt.parameter("charset"));

        MediaType upper = MediaType.parse("text/html; CHARSET=UTF-8");
        assertEquals("UTF-8", upper.charset().orElseThrow());
    }

    @Test
    void extractsBoundaryAndFlagsMultipart() {
        MediaType mt = MediaType.parse("multipart/form-data; boundary=abc");
        assertTrue(mt.isMultipart());
        assertEquals("abc", mt.boundary().orElseThrow());
        assertEquals("multipart/form-data", mt.essence());
    }

    @Test
    void toleratesSurroundingAndSeparatorWhitespace() {
        MediaType mt = MediaType.parse("  text/plain ;  charset = us-ascii  ");
        assertEquals("text", mt.type());
        assertEquals("plain", mt.subtype());
        assertEquals("us-ascii", mt.parameter("charset"));
    }

    @Test
    void parsesQuotedValueContainingSemicolonAndSpaces() {
        MediaType mt = MediaType.parse("application/x-thing; note=\"a; b c\"; charset=utf-8");
        assertEquals("a; b c", mt.parameter("note"));
        assertEquals("utf-8", mt.parameter("charset"));
    }

    @Test
    void decodesBackslashEscapesInQuotedValue() {
        MediaType mt = MediaType.parse("application/x; q=\"a\\\"b\\\\c\"");
        assertEquals("a\"b\\c", mt.parameter("q"));
    }

    @Test
    void preservesParameterOrderAndReRenders() {
        MediaType mt = MediaType.parse("application/x; b=2; a=1; c=3");
        List<String> keys = new ArrayList<>(mt.parameters().keySet());
        assertEquals(List.of("b", "a", "c"), keys);
        assertEquals("application/x; b=2; a=1; c=3", mt.toString());
    }

    @Test
    void toStringQuotesValuesThatNeedQuoting() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("note", "a; b");
        MediaType mt = MediaType.of("application", "x", params);
        assertEquals("application/x; note=\"a; b\"", mt.toString());
    }

    @Test
    void toStringLeavesTokenValuesUnquoted() {
        MediaType mt = MediaType.parse("text/html; charset=utf-8");
        assertEquals("text/html; charset=utf-8", mt.toString());
    }

    @Test
    void matchesConcretePattern() {
        MediaType json = MediaType.parse("application/json");
        assertTrue(json.matches(MediaType.parse("application/json")));
        assertFalse(json.matches(MediaType.parse("application/xml")));
        assertFalse(json.matches(MediaType.parse("text/json")));
    }

    @Test
    void matchesSubtypeWildcard() {
        MediaType json = MediaType.parse("application/json");
        assertTrue(json.matches(MediaType.parse("application/*")));
        assertFalse(json.matches(MediaType.parse("text/*")));
    }

    @Test
    void matchesFullWildcard() {
        MediaType json = MediaType.parse("application/json");
        assertTrue(json.matches(MediaType.parse("*/*")));
        MediaType png = MediaType.parse("image/png");
        assertTrue(png.matches(MediaType.parse("*/*")));
    }

    @Test
    void wildcardDetection() {
        assertTrue(MediaType.parse("application/*").isWildcard());
        assertTrue(MediaType.parse("*/*").isWildcard());
        assertFalse(MediaType.parse("application/json").isWildcard());
    }

    @Test
    void rejectsMissingSlash() {
        assertThrows(MediaType.MediaTypeParseException.class, () -> MediaType.parse("json"));
    }

    @Test
    void rejectsEmptyType() {
        assertThrows(MediaType.MediaTypeParseException.class, () -> MediaType.parse("/json"));
    }

    @Test
    void rejectsEmptySubtype() {
        assertThrows(MediaType.MediaTypeParseException.class, () -> MediaType.parse("application/"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertThrows(MediaType.MediaTypeParseException.class, () -> MediaType.parse(null));
        assertThrows(MediaType.MediaTypeParseException.class, () -> MediaType.parse("   "));
    }

    @Test
    void rejectsMalformedParameters() {
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("text/html; charset"));
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("text/html; =utf-8"));
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("text/html; charset="));
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("text/html;"));
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("text/html; q=\"unterminated"));
    }

    @Test
    void rejectsIllegalTokenCharacters() {
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("app lication/json"));
        assertThrows(MediaType.MediaTypeParseException.class,
                () -> MediaType.parse("application/js on"));
    }

    @Test
    void parameterLookupReturnsNullWhenAbsent() {
        MediaType mt = MediaType.parse("application/json");
        assertNull(mt.parameter("charset"));
        assertNull(mt.parameter(null));
    }

    @Test
    void roundTripsThroughToStringForTokenAndQuotedValues() {
        String[] inputs = {
                "application/json",
                "text/html; charset=utf-8",
                "multipart/form-data; boundary=abc",
                "application/x; note=\"a; b c\"; charset=utf-8",
                "application/x; q=\"a\\\"b\""
        };
        for (String input : inputs) {
            MediaType first = MediaType.parse(input);
            MediaType second = MediaType.parse(first.toString());
            assertEquals(first, second, "round-trip failed for: " + input);
            assertEquals(first.toString(), second.toString());
        }
    }

    @Test
    void parametersViewIsImmutable() {
        MediaType mt = MediaType.parse("text/html; charset=utf-8");
        assertThrows(UnsupportedOperationException.class,
                () -> mt.parameters().put("x", "y"));
    }
}
