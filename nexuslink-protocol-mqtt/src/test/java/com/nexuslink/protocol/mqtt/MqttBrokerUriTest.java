package com.nexuslink.protocol.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline unit tests for {@link MqttBrokerUri}. */
class MqttBrokerUriTest {

    @Test
    void parsesPlainTcpWithExplicitPort() {
        MqttBrokerUri u = MqttBrokerUri.parse("tcp://localhost:1883");
        assertEquals("tcp", u.scheme());
        assertEquals("localhost", u.host());
        assertEquals(1883, u.port());
        assertFalse(u.tls());
        assertFalse(u.websocket());
        assertNull(u.path());
        assertNull(u.username());
        assertNull(u.password());
    }

    @Test
    void appliesTcpDefaultPort() {
        assertEquals(1883, MqttBrokerUri.parse("tcp://broker.example.com").port());
    }

    @Test
    void mqttSchemeIsTcpAlias() {
        MqttBrokerUri u = MqttBrokerUri.parse("mqtt://broker");
        assertEquals("tcp", u.scheme());
        assertEquals(1883, u.port());
        assertFalse(u.tls());
        assertFalse(u.websocket());
    }

    @Test
    void sslDefaultsToPort8883AndTls() {
        MqttBrokerUri u = MqttBrokerUri.parse("ssl://secure-broker");
        assertEquals("ssl", u.scheme());
        assertEquals(8883, u.port());
        assertTrue(u.tls());
        assertFalse(u.websocket());
    }

    @Test
    void tlsAndMqttsAreSslAliasesWithTls() {
        MqttBrokerUri tls = MqttBrokerUri.parse("tls://host");
        MqttBrokerUri mqtts = MqttBrokerUri.parse("mqtts://host");
        assertEquals("ssl", tls.scheme());
        assertEquals("ssl", mqtts.scheme());
        assertTrue(tls.tls());
        assertTrue(mqtts.tls());
        assertEquals(8883, tls.port());
        assertEquals(8883, mqtts.port());
    }

    @Test
    void wsDefaultsToPort80AndWebsocketWithDefaultPath() {
        MqttBrokerUri u = MqttBrokerUri.parse("ws://host");
        assertEquals("ws", u.scheme());
        assertEquals(80, u.port());
        assertTrue(u.websocket());
        assertFalse(u.tls());
        assertEquals("/mqtt", u.path());
    }

    @Test
    void wssDefaultsToPort443WithTlsAndWebsocket() {
        MqttBrokerUri u = MqttBrokerUri.parse("wss://host");
        assertEquals("wss", u.scheme());
        assertEquals(443, u.port());
        assertTrue(u.tls());
        assertTrue(u.websocket());
        assertEquals("/mqtt", u.path());
    }

    @Test
    void parsesExplicitWebsocketPath() {
        MqttBrokerUri u = MqttBrokerUri.parse("wss://host/mqtt");
        assertEquals("/mqtt", u.path());
        assertEquals(443, u.port());

        MqttBrokerUri custom = MqttBrokerUri.parse("ws://host:9001/ws/mqtt");
        assertEquals(9001, custom.port());
        assertEquals("/ws/mqtt", custom.path());
    }

    @Test
    void parsesUserinfoAndPercentDecodes() {
        MqttBrokerUri u = MqttBrokerUri.parse("tcp://alice:s%40cret@broker:1884");
        assertEquals("alice", u.username());
        assertEquals("s@cret", u.password());
        assertEquals("broker", u.host());
        assertEquals(1884, u.port());
    }

    @Test
    void usernameOnlyUserinfo() {
        MqttBrokerUri u = MqttBrokerUri.parse("tcp://alice@broker");
        assertEquals("alice", u.username());
        assertNull(u.password());
    }

    @Test
    void redactedMasksPasswordButNotUsername() {
        MqttBrokerUri u = MqttBrokerUri.parse("tcp://alice:secret@broker:1884");
        String redacted = u.redacted();
        assertTrue(redacted.contains("alice"));
        assertFalse(redacted.contains("secret"));
        assertTrue(redacted.contains("***"));
        assertEquals("tcp://alice:***@broker:1884", redacted);
    }

    @Test
    void redactedWithoutUserinfoIsPlain() {
        assertEquals("tcp://broker:1883", MqttBrokerUri.parse("tcp://broker").redacted());
    }

    @Test
    void normalizedRendersResolvedPortAndEncodesUserinfo() {
        assertEquals("tcp://broker:1883", MqttBrokerUri.parse("tcp://broker").normalized());
        assertEquals("wss://host:443/mqtt", MqttBrokerUri.parse("wss://host").normalized());
        assertEquals("tcp://alice:s%40cret@broker:1884",
                MqttBrokerUri.parse("tcp://alice:s%40cret@broker:1884").normalized());
    }

    @Test
    void toStringEqualsNormalized() {
        MqttBrokerUri u = MqttBrokerUri.parse("ssl://host:8883");
        assertEquals(u.normalized(), u.toString());
    }

    @Test
    void roundTripsThroughToStringForVariousForms() {
        String[] inputs = {
                "tcp://localhost:1883",
                "mqtt://broker",
                "ssl://secure:8883",
                "tls://host",
                "mqtts://host:9999",
                "ws://host",
                "wss://host/mqtt",
                "ws://host:9001/ws/mqtt",
                "tcp://alice:s%40cret@broker:1884",
                "tcp://alice@broker",
        };
        for (String in : inputs) {
            MqttBrokerUri first = MqttBrokerUri.parse(in);
            MqttBrokerUri second = MqttBrokerUri.parse(first.toString());
            assertEquals(first, second, "round-trip failed for " + in);
            assertEquals(first.toString(), second.toString(), "unstable toString for " + in);
        }
    }

    @Test
    void parsesBracketedIpv6Host() {
        MqttBrokerUri u = MqttBrokerUri.parse("tcp://[::1]:1883");
        assertEquals("::1", u.host());
        assertEquals(1883, u.port());
        assertEquals("tcp://[::1]:1883", u.normalized());

        MqttBrokerUri noPort = MqttBrokerUri.parse("ssl://[fe80::1]");
        assertEquals("fe80::1", noPort.host());
        assertEquals(8883, noPort.port());
    }

    @Test
    void schemeMatchingIsCaseInsensitive() {
        assertEquals("ssl", MqttBrokerUri.parse("SSL://host").scheme());
        assertEquals("tcp", MqttBrokerUri.parse("Tcp://host").scheme());
    }

    @Test
    void unknownSchemeThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("http://host:80"));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("amqp://host"));
    }

    @Test
    void missingSchemeSeparatorThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("localhost:1883"));
    }

    @Test
    void missingHostThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://:1883"));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://user@:1883"));
    }

    @Test
    void badPortThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://host:abc"));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://host:0"));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://host:70000"));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://host:"));
    }

    @Test
    void nullOrBlankThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse(null));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("   "));
    }

    @Test
    void pathOnNonWebsocketSchemeThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://host:1883/mqtt"));
    }

    @Test
    void trailingSlashOnTcpIsIgnored() {
        MqttBrokerUri u = MqttBrokerUri.parse("tcp://host:1883/");
        assertNull(u.path());
        assertEquals(1883, u.port());
    }

    @Test
    void badPercentEncodingThrows() {
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://user:p%zz@host"));
        assertThrows(MqttBrokerUriException.class, () -> MqttBrokerUri.parse("tcp://user:p%4@host"));
    }
}
