package com.nexuslink.protocol.rabbitmq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live RabbitMQ publish/consume round-trip against the local {@code test-env} stack.
 * <pre>docker compose -f test-env/docker-compose.yml up -d rabbitmq</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RabbitMqLiveIT {

    @Test
    void publishThenConsume() throws Exception {
        String queue = "nexus-it-" + System.currentTimeMillis();
        try (RabbitMqService svc = new RabbitMqService()) {
            svc.connect("localhost:5672", "nexus", "nexus");
            assertTrue(svc.isConnected());
            svc.declareQueue(queue, false);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<RabbitMqService.Incoming> got = new AtomicReference<>();
            svc.setListener(new RabbitMqService.MessageListener() {
                @Override public void onMessage(RabbitMqService.Incoming m) { got.set(m); latch.countDown(); }
                @Override public void onCancelled(String tag) { }
            });

            // Default exchange ("") routes by queue name.
            svc.publish("", queue, "hello-rabbit");
            svc.consume(queue, true);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "message not received within 15s");
            assertEquals("hello-rabbit", got.get().body());
        }
    }
}
