package com.nexuslink.protocol.mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live MQTT subscribe/publish round-trip against the local {@code test-env} Mosquitto broker.
 * <pre>docker compose -f test-env/docker-compose.yml up -d mosquitto</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MqttLiveIT {

    @Test
    void subscribeThenPublish() throws Exception {
        String topic = "nexus/it/" + System.currentTimeMillis();
        try (MqttService svc = new MqttService()) {
            svc.connect("tcp://localhost:1883", "nexus-it", null, null, true, null, null, 0);
            assertTrue(svc.isConnected());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<MqttService.Incoming> got = new AtomicReference<>();
            svc.setListener(new MqttService.MessageListener() {
                @Override public void onMessage(MqttService.Incoming m) { got.set(m); latch.countDown(); }
                @Override public void onConnectionLost(Throwable cause) { }
            });

            svc.subscribe(topic, 1);
            svc.publish(topic, "hello-mqtt", 1, false);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "message not received within 15s");
            assertEquals("hello-mqtt", got.get().payload());
            assertEquals(topic, got.get().topic());
        }
    }
}
