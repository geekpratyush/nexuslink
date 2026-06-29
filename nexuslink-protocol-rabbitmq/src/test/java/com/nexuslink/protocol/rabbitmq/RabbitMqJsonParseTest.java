package com.nexuslink.protocol.rabbitmq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing sample management-API JSON into the model records (no broker needed). */
class RabbitMqJsonParseTest {

    @Test
    void parsesOverview() {
        String json = """
                {
                  "rabbitmq_version": "3.13.2",
                  "erlang_version": "26.2",
                  "cluster_name": "rabbit@node1",
                  "object_totals": {
                    "queues": 7,
                    "exchanges": 11,
                    "connections": 2,
                    "channels": 3,
                    "consumers": 4
                  },
                  "queue_totals": {
                    "messages": 120,
                    "messages_ready": 100,
                    "messages_unacknowledged": 20
                  }
                }
                """;
        OverviewInfo o = RabbitMqManagementClient.parseOverview(json);
        assertEquals("3.13.2", o.rabbitmqVersion());
        assertEquals("26.2", o.erlangVersion());
        assertEquals("rabbit@node1", o.clusterName());
        assertEquals(7, o.queues());
        assertEquals(11, o.exchanges());
        assertEquals(2, o.connections());
        assertEquals(3, o.channels());
        assertEquals(4, o.consumers());
        assertEquals(120, o.messages());
        assertEquals(100, o.messagesReady());
        assertEquals(20, o.messagesUnacknowledged());
    }

    @Test
    void overviewToleratesMissingTotals() {
        OverviewInfo o = RabbitMqManagementClient.parseOverview("{\"rabbitmq_version\":\"3.13.2\"}");
        assertEquals("3.13.2", o.rabbitmqVersion());
        assertEquals(0, o.queues());
        assertEquals(0, o.messages());
        assertNull(o.clusterName());
    }

    @Test
    void parsesQueueList() {
        String json = """
                [
                  {
                    "name": "orders",
                    "vhost": "/",
                    "type": "classic",
                    "durable": true,
                    "auto_delete": false,
                    "state": "running",
                    "node": "rabbit@node1",
                    "messages": 42,
                    "messages_ready": 40,
                    "messages_unacknowledged": 2,
                    "consumers": 1
                  },
                  {
                    "name": "audit",
                    "vhost": "prod",
                    "durable": false,
                    "auto_delete": true
                  }
                ]
                """;
        List<QueueInfo> queues = RabbitMqManagementClient.parseQueues(json);
        assertEquals(2, queues.size());

        QueueInfo orders = queues.get(0);
        assertEquals("orders", orders.name());
        assertEquals("/", orders.vhost());
        assertEquals("classic", orders.type());
        assertTrue(orders.durable());
        assertFalse(orders.autoDelete());
        assertEquals("running", orders.state());
        assertEquals("rabbit@node1", orders.node());
        assertEquals(42, orders.messages());
        assertEquals(40, orders.messagesReady());
        assertEquals(2, orders.messagesUnacknowledged());
        assertEquals(1, orders.consumers());

        QueueInfo audit = queues.get(1);
        assertEquals("audit", audit.name());
        assertEquals("prod", audit.vhost());
        assertFalse(audit.durable());
        assertTrue(audit.autoDelete());
        // Missing depth fields default to zero.
        assertEquals(0, audit.messages());
        assertEquals(0, audit.consumers());
        assertNull(audit.state());
    }

    @Test
    void parsesSingleQueue() {
        String json = """
                {"name":"dlq","vhost":"/","durable":true,"messages":5,"consumers":0}
                """;
        QueueInfo q = RabbitMqManagementClient.parseQueue(json);
        assertEquals("dlq", q.name());
        assertEquals(5, q.messages());
        assertTrue(q.durable());
    }

    @Test
    void parsesExchanges() {
        String json = """
                [
                  {"name":"","vhost":"/","type":"direct","durable":true,"auto_delete":false,"internal":false},
                  {"name":"events","vhost":"/","type":"topic","durable":true,"auto_delete":false,"internal":false},
                  {"name":"dlx","vhost":"/","type":"fanout","durable":true,"auto_delete":false,"internal":true}
                ]
                """;
        List<ExchangeInfo> exchanges = RabbitMqManagementClient.parseExchanges(json);
        assertEquals(3, exchanges.size());
        assertEquals("", exchanges.get(0).name());
        assertEquals("direct", exchanges.get(0).type());
        assertEquals("topic", exchanges.get(1).type());
        assertTrue(exchanges.get(2).internal());
        assertEquals("fanout", exchanges.get(2).type());
    }

    @Test
    void parsesBindings() {
        String json = """
                [
                  {"source":"","vhost":"/","destination":"orders","destination_type":"queue","routing_key":"orders"},
                  {"source":"events","vhost":"/","destination":"audit","destination_type":"queue","routing_key":"order.*"}
                ]
                """;
        List<BindingInfo> bindings = RabbitMqManagementClient.parseBindings(json);
        assertEquals(2, bindings.size());
        assertEquals("", bindings.get(0).source());
        assertEquals("orders", bindings.get(0).destination());
        assertEquals("queue", bindings.get(0).destinationType());
        assertEquals("events", bindings.get(1).source());
        assertEquals("order.*", bindings.get(1).routingKey());
    }

    @Test
    void parseDashboardAggregatesAllFourBodies() {
        String overview = """
                {
                  "rabbitmq_version": "3.13.2",
                  "object_totals": {"queues": 2, "exchanges": 3, "connections": 1, "channels": 1, "consumers": 1},
                  "queue_totals": {"messages": 7, "messages_ready": 5, "messages_unacknowledged": 2}
                }
                """;
        String queues = """
                [
                  {"name":"orders","vhost":"/","messages":5,"consumers":1},
                  {"name":"audit","vhost":"/","messages":2,"consumers":0}
                ]
                """;
        String exchanges = """
                [
                  {"name":"events","vhost":"/","type":"topic","durable":true},
                  {"name":"dlx","vhost":"/","type":"fanout","durable":true}
                ]
                """;
        String bindings = """
                [
                  {"source":"events","vhost":"/","destination":"audit","destination_type":"queue","routing_key":"order.*"}
                ]
                """;

        RabbitMqManagementClient.Dashboard d =
                RabbitMqManagementClient.parseDashboard(overview, queues, exchanges, bindings);

        assertEquals("3.13.2", d.overview().rabbitmqVersion());
        assertEquals(7, d.overview().messages());
        assertEquals(2, d.queues().size());
        assertEquals("orders", d.queues().get(0).name());
        assertEquals(2, d.exchanges().size());
        assertEquals("topic", d.exchanges().get(0).type());
        assertEquals(1, d.bindings().size());
        assertEquals("order.*", d.bindings().get(0).routingKey());
    }

    @Test
    void parseDashboardToleratesEmptyCollections() {
        RabbitMqManagementClient.Dashboard d =
                RabbitMqManagementClient.parseDashboard("{}", "[]", "[]", "[]");
        assertEquals(0, d.overview().queues());
        assertTrue(d.queues().isEmpty());
        assertTrue(d.exchanges().isEmpty());
        assertTrue(d.bindings().isEmpty());
    }

    @Test
    void invalidJsonRaisesManagementException() {
        assertThrows(RabbitMqManagementException.class,
                () -> RabbitMqManagementClient.parseOverview("not json"));
    }
}
