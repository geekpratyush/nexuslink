package com.nexuslink.protocol.rabbitmq;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link RabbitMqService#factoryFor} — the pure connection-spec seam — so the broker
 * wiring is verified without a live RabbitMQ instance.
 */
class RabbitMqServiceTest {

    @Test
    void bareHostUsesDefaultAmqpPortAndAppliesCredentials() throws Exception {
        ConnectionFactory f = RabbitMqService.factoryFor("broker.example.com", "app", "s3cret");
        assertEquals("broker.example.com", f.getHost());
        assertEquals(5672, f.getPort());
        assertEquals("app", f.getUsername());
        assertEquals("s3cret", f.getPassword());
    }

    @Test
    void hostWithExplicitPortIsParsed() throws Exception {
        ConnectionFactory f = RabbitMqService.factoryFor("10.0.0.5:5673", "", "");
        assertEquals("10.0.0.5", f.getHost());
        assertEquals(5673, f.getPort());
    }

    @Test
    void blankCredentialsLeaveTheFactoryDefaults() throws Exception {
        ConnectionFactory f = RabbitMqService.factoryFor("localhost", "", "");
        // amqp-client's defaults are guest/guest — blank fields must not clobber them.
        assertEquals("guest", f.getUsername());
        assertEquals("guest", f.getPassword());
    }

    @Test
    void amqpUriPopulatesHostPortAndVirtualHost() throws Exception {
        ConnectionFactory f = RabbitMqService.factoryFor("amqp://host.internal:5680/orders", null, null);
        assertEquals("host.internal", f.getHost());
        assertEquals(5680, f.getPort());
        assertEquals("orders", f.getVirtualHost());
    }

    @Test
    void credentialsInUriWinOverExplicitArguments() throws Exception {
        ConnectionFactory f = RabbitMqService.factoryFor(
                "amqp://uriuser:uripass@host.internal/", "argUser", "argPass");
        assertEquals("uriuser", f.getUsername());
        assertEquals("uripass", f.getPassword());
    }

    @Test
    void amqpsUriUsesTheSecurePort() throws Exception {
        ConnectionFactory f = RabbitMqService.factoryFor("amqps://secure.broker/", "u", "p");
        assertEquals(5671, f.getPort());
        assertTrue(f.isSSL());
    }

    @Test
    void blankTargetIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RabbitMqService.factoryFor("   ", "u", "p"));
    }
}
