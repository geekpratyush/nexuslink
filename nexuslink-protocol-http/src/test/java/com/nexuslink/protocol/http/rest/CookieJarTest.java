package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CookieJarTest {

    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");

    private static CookieJar fixedJar() {
        return new CookieJar(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesAndReturnsSimpleCookie() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://api.example.com/"), List.of("sid=abc123"));

        assertEquals("sid=abc123", jar.cookieHeaderFor(URI.create("https://api.example.com/")));
    }

    @Test
    void hostOnlyCookieDoesNotLeakToOtherHosts() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://api.example.com/"), List.of("sid=abc"));

        assertNull(jar.cookieHeaderFor(URI.create("https://other.com/")));
        assertNull(jar.cookieHeaderFor(URI.create("https://sub.api.example.com/")),
                "host-only cookie must not match a subdomain");
    }

    @Test
    void domainCookieMatchesSubdomains() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://api.example.com/"),
                List.of("sid=abc; Domain=example.com"));

        assertEquals("sid=abc", jar.cookieHeaderFor(URI.create("https://example.com/")));
        assertEquals("sid=abc", jar.cookieHeaderFor(URI.create("https://www.example.com/")));
        assertNull(jar.cookieHeaderFor(URI.create("https://example.org/")));
    }

    @Test
    void leadingDotOnDomainIsIgnored() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://api.example.com/"),
                List.of("sid=abc; Domain=.example.com"));

        assertEquals("sid=abc", jar.cookieHeaderFor(URI.create("https://www.example.com/")));
    }

    @Test
    void pathMatchingRestrictsScope() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/app/login"),
                List.of("token=xyz; Path=/app"));

        assertEquals("token=xyz", jar.cookieHeaderFor(URI.create("https://example.com/app")));
        assertEquals("token=xyz", jar.cookieHeaderFor(URI.create("https://example.com/app/sub")));
        assertNull(jar.cookieHeaderFor(URI.create("https://example.com/application")),
                "/app must not match /application (boundary check)");
        assertNull(jar.cookieHeaderFor(URI.create("https://example.com/other")));
    }

    @Test
    void defaultPathIsDerivedFromRequest() {
        // No Path attribute: default-path of /a/b/c is /a/b.
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/a/b/c"), List.of("k=v"));

        assertEquals("k=v", jar.cookieHeaderFor(URI.create("https://example.com/a/b/c")));
        assertEquals("k=v", jar.cookieHeaderFor(URI.create("https://example.com/a/b")));
        assertNull(jar.cookieHeaderFor(URI.create("https://example.com/a")));
    }

    @Test
    void defaultPathAlgorithm() {
        assertEquals("/", CookieJar.defaultPath(""));
        assertEquals("/", CookieJar.defaultPath("/"));
        assertEquals("/", CookieJar.defaultPath("/index.html"));
        assertEquals("/a/b", CookieJar.defaultPath("/a/b/c"));
        assertEquals("/", CookieJar.defaultPath("relative"));
    }

    @Test
    void secureCookieOnlySentOverHttps() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Secure"));

        assertEquals("sid=abc", jar.cookieHeaderFor(URI.create("https://example.com/")));
        assertNull(jar.cookieHeaderFor(URI.create("http://example.com/")));
    }

    @Test
    void expiredViaMaxAgeIsNotStored() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Max-Age=0"));

        assertNull(jar.cookieHeaderFor(URI.create("https://example.com/")));
        assertTrue(jar.all().isEmpty());
    }

    @Test
    void maxAgeKeepsCookieUntilItExpires() {
        // Jar advances against a fixed clock; cookie lives 100s.
        CookieJar liveJar = new CookieJar(Clock.fixed(NOW, ZoneOffset.UTC));
        liveJar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Max-Age=100"));
        assertEquals("sid=abc", liveJar.cookieHeaderFor(URI.create("https://example.com/")));

        // A jar whose clock is already past the expiry sees nothing once stored later.
        CookieJar futureJar = new CookieJar(Clock.fixed(NOW.plusSeconds(200), ZoneOffset.UTC));
        futureJar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Max-Age=-5"));
        assertNull(futureJar.cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void expiresInThePastDeletesCookie() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT"));

        assertNull(jar.cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void expiresInTheFutureIsHonoured() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Expires=Thu, 01 Jan 2099 00:00:00 GMT"));

        assertEquals("sid=abc", jar.cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void maxAgeTakesPrecedenceOverExpires() {
        // Expires is past, but Max-Age keeps it alive => cookie survives.
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"),
                List.of("sid=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT; Max-Age=3600"));

        assertEquals("sid=abc", jar.cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void multipleCookiesAreJoinedLongestPathFirst() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/app/deep"), List.of(
                "a=1; Path=/",
                "b=2; Path=/app",
                "c=3; Path=/app/deep"));

        // Longer paths first per RFC 6265 §5.4.
        assertEquals("c=3; b=2; a=1",
                jar.cookieHeaderFor(URI.create("https://example.com/app/deep")));
    }

    @Test
    void newCookieReplacesSameIdentity() {
        CookieJar jar = fixedJar();
        URI uri = URI.create("https://example.com/");
        jar.storeFrom(uri, List.of("sid=old"));
        jar.storeFrom(uri, List.of("sid=new"));

        assertEquals("sid=new", jar.cookieHeaderFor(uri));
        assertEquals(1, jar.all().size());
    }

    @Test
    void differentPathsAreDistinctCookies() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/a"), List.of("sid=1; Path=/a"));
        jar.storeFrom(URI.create("https://example.com/b"), List.of("sid=2; Path=/b"));

        assertEquals(2, jar.all().size());
        assertEquals("sid=1", jar.cookieHeaderFor(URI.create("https://example.com/a")));
        assertEquals("sid=2", jar.cookieHeaderFor(URI.create("https://example.com/b")));
    }

    @Test
    void clearRemovesEverything() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"), List.of("a=1", "b=2"));
        assertEquals(2, jar.all().size());

        jar.clear();
        assertTrue(jar.all().isEmpty());
        assertNull(jar.cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void malformedHeadersAreSkipped() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"), java.util.Arrays.asList(
                "",            // blank
                "novalue",     // no '='
                "=orphan",     // empty name
                "good=1"));

        assertEquals("good=1", jar.cookieHeaderFor(URI.create("https://example.com/")));
        assertEquals(1, jar.all().size());
    }

    @Test
    void nullArgumentsAreTolerated() {
        CookieJar jar = fixedJar();
        assertDoesNotThrow(() -> jar.storeFrom(null, List.of("a=1")));
        assertDoesNotThrow(() -> jar.storeFrom(URI.create("https://x/"), null));
        assertNull(jar.cookieHeaderFor(null));
        assertNull(jar.cookieHeaderFor(URI.create("file:///tmp")));
    }

    @Test
    void valueWithEqualsSignIsPreserved() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"), List.of("token=a=b=c"));

        assertEquals("token=a=b=c", jar.cookieHeaderFor(URI.create("https://example.com/")));
    }

    @Test
    void allReturnsImmutableSnapshot() {
        CookieJar jar = fixedJar();
        jar.storeFrom(URI.create("https://example.com/"), List.of("a=1"));
        List<CookieJar.Cookie> snapshot = jar.all();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(null));
    }
}
