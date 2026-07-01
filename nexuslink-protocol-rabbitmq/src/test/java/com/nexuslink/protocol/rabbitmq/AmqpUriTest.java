package com.nexuslink.protocol.rabbitmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure {@link AmqpUri} connection-URI parser (no broker required). */
class AmqpUriTest {

    @Test
    void parsesFullAmqpUriWithCredentialsPortAndVhost() {
        AmqpUri uri = AmqpUri.parse("amqp://guest:guest@localhost:5672/myvhost");
        assertEquals("amqp", uri.scheme());
        assertFalse(uri.tls());
        assertEquals("localhost", uri.host());
        assertEquals(5672, uri.port());
        assertEquals("guest", uri.username());
        assertEquals("guest", uri.password());
        assertEquals("myvhost", uri.vhost());
        assertTrue(uri.params().isEmpty());
    }

    @Test
    void amqpsImpliesTlsAndDefaultPort5671AndDefaultVhost() {
        AmqpUri uri = AmqpUri.parse("amqps://host");
        assertEquals("amqps", uri.scheme());
        assertTrue(uri.tls());
        assertEquals("host", uri.host());
        assertEquals(5671, uri.port());
        assertNull(uri.username());
        assertNull(uri.password());
        assertEquals("/", uri.vhost());
    }

    @Test
    void amqpDefaultsPortTo5672() {
        assertEquals(5672, AmqpUri.parse("amqp://host").port());
    }

    @Test
    void absentPathMeansDefaultVhostSlash() {
        assertEquals("/", AmqpUri.parse("amqp://host").vhost());
    }

    @Test
    void emptyPathMeansEmptyStringVhost() {
        assertEquals("", AmqpUri.parse("amqp://host/").vhost());
    }

    @Test
    void encodedSlashInVhostDecodesToLiteralSlash() {
        assertEquals("/", AmqpUri.parse("amqp://host/%2f").vhost());
        assertEquals("a/b", AmqpUri.parse("amqp://host/a%2fb").vhost());
        assertEquals("/", AmqpUri.parse("amqp://host/%2F").vhost());
    }

    @Test
    void rawSecondSlashInPathIsRejected() {
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://host/a/b"));
    }

    @Test
    void percentEncodedPasswordIsDecoded() {
        AmqpUri uri = AmqpUri.parse("amqp://user:p%40ss%2Fword@host");
        assertEquals("user", uri.username());
        assertEquals("p@ss/word", uri.password());
    }

    @Test
    void percentEncodedUsernameAndVhostAreDecoded() {
        AmqpUri uri = AmqpUri.parse("amqp://user%20name@host/v%20host");
        assertEquals("user name", uri.username());
        assertEquals("v host", uri.vhost());
    }

    @Test
    void usernameWithoutPasswordHasNullPassword() {
        AmqpUri uri = AmqpUri.parse("amqp://onlyuser@host");
        assertEquals("onlyuser", uri.username());
        assertNull(uri.password());
    }

    @Test
    void queryParametersAreParsedAndTyped() {
        AmqpUri uri = AmqpUri.parse(
                "amqp://host/vh?heartbeat=30&connection_timeout=15000&channel_max=100");
        assertEquals("30", uri.param("heartbeat"));
        assertEquals(OptionalInt.of(30), uri.heartbeat());
        assertEquals(OptionalInt.of(15000), uri.connectionTimeout());
        assertEquals(OptionalInt.of(100), uri.channelMax());
        assertEquals(3, uri.params().size());
    }

    @Test
    void absentQueryParamIsEmptyOptional() {
        AmqpUri uri = AmqpUri.parse("amqp://host");
        assertEquals(OptionalInt.empty(), uri.heartbeat());
        assertNull(uri.param("heartbeat"));
    }

    @Test
    void nonIntegerQueryParamThrowsWhenTyped() {
        AmqpUri uri = AmqpUri.parse("amqp://host?heartbeat=soon");
        assertEquals("soon", uri.param("heartbeat"));
        assertThrows(AmqpUriException.class, uri::heartbeat);
    }

    @Test
    void valuelessQueryParamKeepsEmptyValue() {
        AmqpUri uri = AmqpUri.parse("amqp://host?flag");
        assertEquals("", uri.param("flag"));
        assertTrue(uri.params().containsKey("flag"));
    }

    @Test
    void queryParamOrderIsPreserved() {
        AmqpUri uri = AmqpUri.parse("amqp://host?c=3&a=1&b=2");
        assertEquals("[c, a, b]", uri.params().keySet().toString());
    }

    @Test
    void defaultHostIsLocalhostWhenOmitted() {
        AmqpUri uri = AmqpUri.parse("amqp://user:pass@:5672/vh");
        assertEquals("localhost", uri.host());
        assertEquals(5672, uri.port());
        assertEquals("user", uri.username());
    }

    @Test
    void explicitPortOverridesSchemeDefault() {
        assertEquals(5673, AmqpUri.parse("amqp://host:5673").port());
        assertEquals(5673, AmqpUri.parse("amqps://host:5673").port());
    }

    @Test
    void ipv6HostLiteralIsSupported() {
        AmqpUri uri = AmqpUri.parse("amqp://[::1]:5672/vh");
        assertEquals("::1", uri.host());
        assertEquals(5672, uri.port());
        assertEquals("vh", uri.vhost());
    }

    @Test
    void ipv6HostWithoutPortUsesSchemeDefault() {
        AmqpUri uri = AmqpUri.parse("amqps://[fe80::1]");
        assertEquals("fe80::1", uri.host());
        assertEquals(5671, uri.port());
    }

    @Test
    void passwordIsMaskedInToStringAndRedacted() {
        AmqpUri uri = AmqpUri.parse("amqp://user:secret@host/vh");
        assertFalse(uri.toString().contains("secret"));
        assertTrue(uri.toString().contains("****"));
        assertFalse(uri.redacted().contains("secret"));
        assertTrue(uri.redacted().contains("****"));
        // The real password is still available programmatically.
        assertEquals("secret", uri.password());
    }

    @Test
    void redactedRoundTripsBackToEquivalentUri() {
        AmqpUri original = AmqpUri.parse("amqps://u:p@example.com:5671/prod?heartbeat=60");
        AmqpUri reparsed = AmqpUri.parse(original.redacted());
        assertEquals("amqps", reparsed.scheme());
        assertEquals("example.com", reparsed.host());
        assertEquals(5671, reparsed.port());
        assertEquals("u", reparsed.username());
        assertEquals("****", reparsed.password()); // password was masked
        assertEquals("prod", reparsed.vhost());
        assertEquals(OptionalInt.of(60), reparsed.heartbeat());
    }

    @Test
    void canonicalStringEncodesDefaultVhostAsSlash() {
        // Absent path -> default vhost "/" -> renders as an escaped slash segment.
        assertEquals("amqp://host:5672/%2F", AmqpUri.parse("amqp://host").toString());
        // Empty path -> empty vhost -> renders as a trailing slash with no segment.
        assertEquals("amqp://host:5672/", AmqpUri.parse("amqp://host/").toString());
    }

    @Test
    void badSchemeThrows() {
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("http://host"));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqpx://host"));
    }

    @Test
    void missingSchemeSeparatorThrows() {
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("localhost:5672"));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("://host"));
    }

    @Test
    void nullOrBlankUriThrows() {
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse(null));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("   "));
    }

    @Test
    void badPortThrows() {
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://host:notaport"));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://host:70000/vh"));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://host:0"));
    }

    @Test
    void malformedPercentEscapeThrows() {
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://host/%2"));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://host/%zz"));
        assertThrows(AmqpUriException.class, () -> AmqpUri.parse("amqp://u%GG@host"));
    }

    @Test
    void schemeIsCaseInsensitive() {
        AmqpUri uri = AmqpUri.parse("AMQPS://host");
        assertEquals("amqps", uri.scheme());
        assertTrue(uri.tls());
    }

    @Test
    void equalsAndHashCodeMatchForEquivalentUris() {
        AmqpUri a = AmqpUri.parse("amqp://u:p@host:5672/vh?x=1");
        AmqpUri b = AmqpUri.parse("amqp://u:p@host:5672/vh?x=1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
