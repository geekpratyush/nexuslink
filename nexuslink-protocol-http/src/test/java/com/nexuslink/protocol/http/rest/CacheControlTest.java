package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CacheControl} against the directive examples in
 * <a href="https://www.rfc-editor.org/rfc/rfc7234#section-5.2">RFC 7234 &sect;5.2</a>
 * (and the related RFC 8246 {@code immutable} and RFC 5861 stale-* directives).
 */
class CacheControlTest {

    @Test
    void singleBooleanDirectiveNoCache() {
        CacheControl cc = CacheControl.parse("no-cache");
        assertTrue(cc.noCache());
        assertTrue(cc.has("no-cache"));
        assertFalse(cc.noStore());
        assertFalse(cc.isPublic());
        assertEquals(OptionalLong.empty(), cc.maxAge());
        assertFalse(cc.isEmpty());
    }

    @Test
    void maxAgeValue() {
        CacheControl cc = CacheControl.parse("max-age=3600");
        assertEquals(OptionalLong.of(3600L), cc.maxAge());
        assertTrue(cc.has("max-age"));
        assertEquals("3600", cc.directive("max-age"));
        assertFalse(cc.noCache());
    }

    @Test
    void publicMaxAgeImmutable() {
        CacheControl cc = CacheControl.parse("public, max-age=31536000, immutable");
        assertTrue(cc.isPublic());
        assertEquals(OptionalLong.of(31536000L), cc.maxAge());
        assertTrue(cc.immutable());
        assertFalse(cc.isPrivate());
    }

    @Test
    void privateNoStore() {
        CacheControl cc = CacheControl.parse("private, no-store");
        assertTrue(cc.isPrivate());
        assertTrue(cc.noStore());
        assertFalse(cc.isPublic());
        // 'private' present but without a field-list argument.
        assertTrue(cc.has("private"));
        assertEquals(null, cc.directive("private"));
    }

    @Test
    void bothSMaxAgeAndMaxAgeAreReadable() {
        CacheControl cc = CacheControl.parse("s-maxage=60, max-age=30");
        assertEquals(OptionalLong.of(60L), cc.sMaxAge());
        assertEquals(OptionalLong.of(30L), cc.maxAge());
    }

    @Test
    void maxStaleWithValue() {
        CacheControl cc = CacheControl.parse("max-stale=120");
        assertTrue(cc.has("max-stale"));
        assertEquals(OptionalLong.of(120L), cc.maxStale());
    }

    @Test
    void maxStaleWithoutValueMeansAny() {
        CacheControl cc = CacheControl.parse("max-stale");
        assertTrue(cc.has("max-stale"));
        // Present but valueless: "any" staleness acceptable, so no numeric bound.
        assertEquals(OptionalLong.empty(), cc.maxStale());
        assertEquals(null, cc.directive("max-stale"));
    }

    @Test
    void quotedFieldListArgument() {
        CacheControl cc = CacheControl.parse("no-cache=\"Set-Cookie\"");
        assertTrue(cc.noCache());
        assertEquals("Set-Cookie", cc.directive("no-cache"));
    }

    @Test
    void quotedArgumentWithCommaIsNotASeparator() {
        CacheControl cc = CacheControl.parse("private=\"Set-Cookie, WWW-Authenticate\", max-age=10");
        assertEquals("Set-Cookie, WWW-Authenticate", cc.directive("private"));
        assertEquals(OptionalLong.of(10L), cc.maxAge());
    }

    @Test
    void directiveNamesAreCaseInsensitive() {
        CacheControl cc = CacheControl.parse("No-Cache, MAX-AGE=42, Public");
        assertTrue(cc.noCache());
        assertTrue(cc.isPublic());
        assertEquals(OptionalLong.of(42L), cc.maxAge());
        // Lookups are case-insensitive too.
        assertTrue(cc.has("NO-CACHE"));
        assertEquals("42", cc.directive("Max-Age"));
    }

    @Test
    void unknownDirectivePreserved() {
        CacheControl cc = CacheControl.parse("max-age=5, x-vendor-flag=on, surrogate-control");
        assertEquals(OptionalLong.of(5L), cc.maxAge());
        assertTrue(cc.has("x-vendor-flag"));
        assertEquals("on", cc.directive("x-vendor-flag"));
        assertTrue(cc.has("surrogate-control"));
        assertEquals(null, cc.directive("surrogate-control"));
    }

    @Test
    void whitespaceTolerance() {
        CacheControl cc = CacheControl.parse("  public ,   max-age = 100 ,, no-transform  ");
        assertTrue(cc.isPublic());
        assertEquals(OptionalLong.of(100L), cc.maxAge());
        assertTrue(cc.noTransform());
        assertEquals(3, cc.directives().size());
    }

    @Test
    void emptyAndBlankYieldAllAbsent() {
        for (String in : new String[] {null, "", "   ", ",", " , , "}) {
            CacheControl cc = CacheControl.parse(in);
            assertTrue(cc.isEmpty(), "expected empty for [" + in + "]");
            assertFalse(cc.noCache());
            assertFalse(cc.noStore());
            assertFalse(cc.isPublic());
            assertFalse(cc.isPrivate());
            assertEquals(OptionalLong.empty(), cc.maxAge());
            assertEquals(OptionalLong.empty(), cc.sMaxAge());
            assertFalse(cc.has("max-age"));
        }
    }

    @Test
    void malformedDeltaSecondsIsLenient() {
        CacheControl cc = CacheControl.parse("max-age=abc, s-maxage=-5, min-fresh=");
        // Value is preserved verbatim...
        assertTrue(cc.has("max-age"));
        assertEquals("abc", cc.directive("max-age"));
        // ...but numeric accessors report it as absent rather than throwing.
        assertEquals(OptionalLong.empty(), cc.maxAge());
        assertEquals(OptionalLong.empty(), cc.sMaxAge());
        assertEquals(OptionalLong.empty(), cc.minFresh());
    }

    @Test
    void firstOccurrenceOfRepeatedDirectiveWins() {
        CacheControl cc = CacheControl.parse("max-age=1, max-age=2");
        assertEquals(OptionalLong.of(1L), cc.maxAge());
    }

    @Test
    void allBooleanDirectivesRecognised() {
        CacheControl cc = CacheControl.parse(
                "no-cache, no-store, no-transform, only-if-cached, must-revalidate, "
                        + "public, private, proxy-revalidate, immutable, must-understand");
        assertTrue(cc.noCache());
        assertTrue(cc.noStore());
        assertTrue(cc.noTransform());
        assertTrue(cc.onlyIfCached());
        assertTrue(cc.mustRevalidate());
        assertTrue(cc.isPublic());
        assertTrue(cc.isPrivate());
        assertTrue(cc.proxyRevalidate());
        assertTrue(cc.immutable());
        assertTrue(cc.mustUnderstand());
    }

    @Test
    void staleWhileRevalidateAndStaleIfError() {
        CacheControl cc = CacheControl.parse("max-age=600, stale-while-revalidate=30, stale-if-error=86400");
        assertEquals(OptionalLong.of(30L), cc.staleWhileRevalidate());
        assertEquals(OptionalLong.of(86400L), cc.staleIfError());
    }

    @Test
    void toStringReRendersAndRoundTrips() {
        CacheControl cc = CacheControl.parse("public, max-age=31536000, immutable");
        assertEquals("public, max-age=31536000, immutable", cc.toString());

        // A field-list value with a comma must be re-quoted so it round-trips.
        CacheControl q = CacheControl.parse("no-cache=\"Set-Cookie, X-Trace\"");
        assertEquals("no-cache=\"Set-Cookie, X-Trace\"", q.toString());
        CacheControl reparsed = CacheControl.parse(q.toString());
        assertEquals("Set-Cookie, X-Trace", reparsed.directive("no-cache"));
    }
}
