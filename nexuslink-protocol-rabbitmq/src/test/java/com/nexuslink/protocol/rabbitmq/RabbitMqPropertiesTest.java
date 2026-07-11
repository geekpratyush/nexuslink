package com.nexuslink.protocol.rabbitmq;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the pure {@link RabbitMqService#buildProperties} message-properties builder. */
class RabbitMqPropertiesTest {

    @Test
    void alwaysPersistent() {
        AMQP.BasicProperties p = RabbitMqService.buildProperties(null, null, null);
        assertEquals(2, p.getDeliveryMode(), "deliveryMode 2 = persistent");
        assertNull(p.getContentType());
        assertNull(p.getCorrelationId());
        assertNull(p.getHeaders());
    }

    @Test
    void setsContentTypeAndCorrelationId() {
        AMQP.BasicProperties p = RabbitMqService.buildProperties("application/json", "corr-42", null);
        assertEquals("application/json", p.getContentType());
        assertEquals("corr-42", p.getCorrelationId());
    }

    @Test
    void trimsAndOmitsBlankValues() {
        AMQP.BasicProperties p = RabbitMqService.buildProperties("  text/plain ", "   ", Map.of());
        assertEquals("text/plain", p.getContentType());
        assertNull(p.getCorrelationId(), "blank correlation id is omitted");
        assertNull(p.getHeaders(), "empty header map is omitted");
    }

    @Test
    void copiesHeadersSkippingBlankKeys() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-source", "nexuslink");
        headers.put("  ", "ignored");
        AMQP.BasicProperties p = RabbitMqService.buildProperties(null, null, headers);
        assertNotNull(p.getHeaders());
        assertEquals("nexuslink", p.getHeaders().get("x-source"));
        assertFalse(p.getHeaders().containsKey("  "));
        assertEquals(1, p.getHeaders().size());
    }
}
