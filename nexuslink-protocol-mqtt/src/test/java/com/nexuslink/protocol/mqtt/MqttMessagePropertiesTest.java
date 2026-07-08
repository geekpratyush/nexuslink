package com.nexuslink.protocol.mqtt;

import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MqttMessageProperties} and the Paho v5 property mapping in
 * {@link MqttService#toPaho}/{@link MqttService#fromPaho}. All pure — no broker required.
 */
class MqttMessagePropertiesTest {

    // --- record semantics ----------------------------------------------------------------------

    @Test
    void emptyIsEmpty() {
        assertTrue(MqttMessageProperties.EMPTY.isEmpty());
        assertTrue(MqttMessageProperties.builder().isEmpty());
        assertTrue(MqttMessageProperties.EMPTY.userProperties().isEmpty());
        assertNull(MqttMessageProperties.EMPTY.correlationData());
    }

    @Test
    void nullListNormalisesToEmpty() {
        MqttMessageProperties p = new MqttMessageProperties(null, null, null, null, null);
        assertNotNull(p.userProperties());
        assertTrue(p.userProperties().isEmpty());
        assertTrue(p.isEmpty());
    }

    @Test
    void fluentBuildersAreImmutableAndCumulative() {
        MqttMessageProperties p = MqttMessageProperties.builder()
                .withUserProperty("a", "1")
                .withUserProperty("b", "2")
                .withMessageExpiryInterval(60L)
                .withContentType("application/json")
                .withResponseTopic("reply/here")
                .withCorrelationData("corr-42");

        assertFalse(p.isEmpty());
        assertEquals(2, p.userProperties().size());
        assertEquals(new MqttMessageProperties.UserProperty("a", "1"), p.userProperties().get(0));
        assertEquals(60L, p.messageExpiryInterval());
        assertEquals("application/json", p.contentType());
        assertEquals("reply/here", p.responseTopic());
        assertArrayEquals("corr-42".getBytes(StandardCharsets.UTF_8), p.correlationData());

        // The EMPTY starting point is untouched by the builders.
        assertTrue(MqttMessageProperties.EMPTY.isEmpty());
    }

    @Test
    void userPropertyOrderAndDuplicatesPreserved() {
        MqttMessageProperties p = MqttMessageProperties.builder()
                .withUserProperty("k", "1")
                .withUserProperty("k", "2");
        assertEquals(List.of(
                new MqttMessageProperties.UserProperty("k", "1"),
                new MqttMessageProperties.UserProperty("k", "2")), p.userProperties());
    }

    @Test
    void userPropertyRejectsNulls() {
        assertThrows(NullPointerException.class, () -> new MqttMessageProperties.UserProperty(null, "v"));
        assertThrows(NullPointerException.class, () -> new MqttMessageProperties.UserProperty("k", null));
    }

    @Test
    void correlationDataIsDefensivelyCopied() {
        byte[] src = {1, 2, 3};
        MqttMessageProperties p = MqttMessageProperties.builder().withCorrelationData(src);
        src[0] = 9;                       // mutate the source after construction
        assertArrayEquals(new byte[] {1, 2, 3}, p.correlationData());
        p.correlationData()[0] = 8;       // mutate a returned copy
        assertArrayEquals(new byte[] {1, 2, 3}, p.correlationData());
    }

    @Test
    void equalsAndHashCodeUseValueSemanticsForBytes() {
        MqttMessageProperties a = MqttMessageProperties.builder()
                .withUserProperty("x", "y").withCorrelationData("c");
        MqttMessageProperties b = MqttMessageProperties.builder()
                .withUserProperty("x", "y").withCorrelationData("c");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        MqttMessageProperties c = MqttMessageProperties.builder()
                .withUserProperty("x", "y").withCorrelationData("different");
        assertNotEquals(a, c);
    }

    // --- Paho mapping round-trip ---------------------------------------------------------------

    @Test
    void toPahoAndBackRoundTrips() {
        MqttMessageProperties original = MqttMessageProperties.builder()
                .withUserProperty("trace", "abc")
                .withUserProperty("hop", "1")
                .withMessageExpiryInterval(120L)
                .withContentType("text/plain")
                .withResponseTopic("resp/topic")
                .withCorrelationData("correlate-me");

        MqttProperties paho = MqttService.toPaho(original);
        assertEquals("text/plain", paho.getContentType());
        assertEquals(120L, paho.getMessageExpiryInterval());
        assertEquals("resp/topic", paho.getResponseTopic());
        assertEquals(2, paho.getUserProperties().size());
        assertEquals("trace", paho.getUserProperties().get(0).getKey());

        MqttMessageProperties back = MqttService.fromPaho(paho);
        assertEquals(original, back);
    }

    @Test
    void emptyPropertiesMapToEmptyPaho() {
        MqttProperties paho = MqttService.toPaho(MqttMessageProperties.EMPTY);
        assertNull(paho.getContentType());
        assertNull(paho.getMessageExpiryInterval());
        assertTrue(paho.getUserProperties() == null || paho.getUserProperties().isEmpty());
    }

    @Test
    void fromPahoOnEmptyReturnsEmpty() {
        assertEquals(MqttMessageProperties.EMPTY, MqttService.fromPaho(new MqttProperties()));
        assertEquals(MqttMessageProperties.EMPTY, MqttService.fromPaho(null));
    }
}
