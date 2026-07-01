package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link LinkHeader} against RFC 8288 (Web Linking): single and multiple links,
 * quoted values containing separators, multi-valued and case-insensitive attributes,
 * {@code byRel} pagination lookups and lenient handling of blank / malformed input.
 */
class LinkHeaderTest {

    @Test
    void parsesSingleLinkWithRelNext() {
        LinkHeader h = LinkHeader.parse("<https://api.example.com/users?page=2>; rel=\"next\"");
        assertEquals(1, h.size());
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("https://api.example.com/users?page=2", link.uri());
        assertEquals("next", link.rel());
        assertEquals(List.of("next"), link.rels());
        assertNull(link.title());
    }

    @Test
    void parsesMultipleCommaSeparatedLinks() {
        LinkHeader h = LinkHeader.parse(
                "<https://example.com/a>; rel=\"prev\", <https://example.com/c>; rel=\"next\"");
        assertEquals(2, h.size());
        assertEquals("https://example.com/a", h.links().get(0).uri());
        assertEquals("prev", h.links().get(0).rel());
        assertEquals("https://example.com/c", h.links().get(1).uri());
        assertEquals("next", h.links().get(1).rel());
    }

    @Test
    void unquotesValueContainingCommaAndSemicolon() {
        LinkHeader h = LinkHeader.parse(
                "<https://example.com/x>; rel=\"next\"; title=\"Part 2, chapter 3; final\"");
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("Part 2, chapter 3; final", link.title());
        // The embedded comma must not have split the header into two links.
        assertEquals(1, h.size());
    }

    @Test
    void decodesBackslashEscapesInQuotedValue() {
        LinkHeader h = LinkHeader.parse("<https://example.com/x>; title=\"a \\\"quoted\\\" word\"");
        assertEquals("a \"quoted\" word", h.first().orElseThrow().title());
    }

    @Test
    void relWithTwoSpaceSeparatedValues() {
        LinkHeader h = LinkHeader.parse("<https://example.com/copyright>; rel=\"copyright license\"");
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("copyright license", link.rel());
        assertEquals(List.of("copyright", "license"), link.rels());
        assertTrue(link.hasRel("license"));
        assertTrue(link.hasRel("copyright"));
        assertFalse(link.hasRel("next"));
    }

    @Test
    void paramNamesAreCaseInsensitive() {
        LinkHeader h = LinkHeader.parse("<https://example.com/x>; REL=\"next\"; Title=\"Hi\"");
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("next", link.rel());
        assertEquals("Hi", link.title());
        assertEquals("next", link.param("rel"));
        assertEquals("next", link.param("Rel"));
    }

    @Test
    void tokenValuesWithoutQuotesAreAccepted() {
        LinkHeader h = LinkHeader.parse("<https://example.com/x>; rel=next; type=text/html");
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("next", link.rel());
        assertEquals("text/html", link.type());
    }

    @Test
    void byRelFindsNextAndPrev() {
        LinkHeader h = LinkHeader.parse(
                "<https://example.com/1>; rel=\"prev\", <https://example.com/3>; rel=\"next\"");
        assertEquals("https://example.com/3", h.byRel("next").orElseThrow().uri());
        assertEquals("https://example.com/1", h.byRel("prev").orElseThrow().uri());
        // Case-insensitive relation lookup.
        assertEquals("https://example.com/3", h.byRel("NEXT").orElseThrow().uri());
        assertTrue(h.byRel("last").isEmpty());
    }

    @Test
    void githubStylePaginationHeader() {
        LinkHeader h = LinkHeader.parse(
                "<https://api.github.com/user/repos?page=2>; rel=\"next\", "
                        + "<https://api.github.com/user/repos?page=34>; rel=\"last\"");
        assertEquals(2, h.size());
        assertEquals("https://api.github.com/user/repos?page=2", h.byRel("next").orElseThrow().uri());
        assertEquals("https://api.github.com/user/repos?page=34", h.byRel("last").orElseThrow().uri());
        assertTrue(h.byRel("prev").isEmpty());
    }

    @Test
    void hreflangAndTypeAccessors() {
        LinkHeader h = LinkHeader.parse(
                "<https://example.org/de>; rel=\"alternate\"; hreflang=\"de\"; type=\"text/html\"");
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("de", link.hreflang());
        assertEquals("text/html", link.type());
        assertEquals("alternate", link.rel());
    }

    @Test
    void blankInputYieldsEmptyList() {
        assertTrue(LinkHeader.parse("").isEmpty());
        assertTrue(LinkHeader.parse("   ").isEmpty());
        assertTrue(LinkHeader.parse(null).isEmpty());
        assertEquals(List.of(), LinkHeader.parse(null).links());
        assertTrue(LinkHeader.parse("").first().isEmpty());
    }

    @Test
    void skipsMalformedEntryWithoutUri() {
        // Middle entry has no <uri-ref> and must be skipped, not abort the whole header.
        LinkHeader h = LinkHeader.parse(
                "<https://example.com/a>; rel=\"first\", garbage; rel=\"x\", "
                        + "<https://example.com/b>; rel=\"last\"");
        assertEquals(2, h.size());
        assertEquals("first", h.links().get(0).rel());
        assertEquals("last", h.links().get(1).rel());
    }

    @Test
    void linkWithNoParamsHasEmptyRels() {
        LinkHeader h = LinkHeader.parse("<https://example.com/bare>");
        LinkHeader.Link link = h.first().orElseThrow();
        assertEquals("https://example.com/bare", link.uri());
        assertNull(link.rel());
        assertTrue(link.rels().isEmpty());
        assertTrue(link.params().isEmpty());
    }

    @Test
    void firstRepeatedAttributeWins() {
        LinkHeader h = LinkHeader.parse("<https://example.com/x>; rel=\"next\"; rel=\"prev\"");
        assertEquals("next", h.first().orElseThrow().rel());
    }
}
