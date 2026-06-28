package com.nexuslink.protocol.rabbitmq;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** URL/path building, vhost encoding, and Basic-auth header formatting (no broker needed). */
class RabbitMqManagementPathsTest {

    private RabbitMqManagementClient client(String apiBase) {
        return new RabbitMqManagementClient(apiBase, "guest", "guest", true);
    }

    @Test
    void apiBaseUsesPlainHttpAndPort() {
        assertEquals("http://localhost:15672/api",
                RabbitMqManagementClient.apiBase("localhost", RabbitMqManagementClient.DEFAULT_PORT));
        assertEquals("http://broker:8080/api", RabbitMqManagementClient.apiBase("broker", 8080));
    }

    @Test
    void defaultPortConstructorBuildsExpectedPaths() {
        RabbitMqManagementClient c = new RabbitMqManagementClient("rabbit.example.com", "guest", "guest");
        assertEquals("http://rabbit.example.com:15672/api/overview", c.overviewPath());
    }

    @Test
    void overviewAndCollectionPaths() {
        RabbitMqManagementClient c = client("http://localhost:15672/api");
        assertEquals("http://localhost:15672/api/overview", c.overviewPath());
        assertEquals("http://localhost:15672/api/queues", c.queuesPath(null));
        assertEquals("http://localhost:15672/api/exchanges", c.exchangesPath(null));
        assertEquals("http://localhost:15672/api/bindings", c.bindingsPath(null));
    }

    @Test
    void defaultVhostIsPercentEncoded() {
        RabbitMqManagementClient c = client("http://localhost:15672/api");
        assertEquals("http://localhost:15672/api/queues/%2F", c.queuesPath("/"));
        assertEquals("http://localhost:15672/api/queues/%2F/orders", c.queuePath("/", "orders"));
        assertEquals("http://localhost:15672/api/queues/%2F/orders/contents",
                c.purgeQueuePath("/", "orders"));
        assertEquals("http://localhost:15672/api/exchanges/%2F", c.exchangesPath("/"));
        assertEquals("http://localhost:15672/api/bindings/%2F", c.bindingsPath("/"));
    }

    @Test
    void namedVhostAndSpacesEncoded() {
        RabbitMqManagementClient c = client("http://localhost:15672/api");
        assertEquals("http://localhost:15672/api/queues/prod/work%20queue", c.queuePath("prod", "work queue"));
    }

    @Test
    void encodeSegmentUsesPercent20NotPlus() {
        assertEquals("%2F", RabbitMqManagementClient.encodeSegment("/"));
        assertEquals("a%20b", RabbitMqManagementClient.encodeSegment("a b"));
        assertEquals("q.1-2_3", RabbitMqManagementClient.encodeSegment("q.1-2_3"));
        assertEquals("", RabbitMqManagementClient.encodeSegment(""));
    }

    @Test
    void trailingSlashInApiBaseIsStripped() {
        RabbitMqManagementClient c = client("http://localhost:15672/api/");
        assertEquals("http://localhost:15672/api/overview", c.overviewPath());
    }

    @Test
    void basicAuthHeaderIsBase64OfUserColonPassword() {
        String header = RabbitMqManagementClient.basicAuthHeader("guest", "guest");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("guest:guest".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header);
        assertEquals("guest:guest", new String(
                Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8));
    }

    @Test
    void blankHostAndApiBaseRejected() {
        assertThrows(IllegalArgumentException.class, () -> RabbitMqManagementClient.apiBase("", 15672));
        assertThrows(IllegalArgumentException.class, () -> client("  "));
    }
}
