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

    @Test
    void publishWithV5PropertiesRoundTrips() throws Exception {
        String topic = "nexus/it/v5/" + System.currentTimeMillis();
        try (MqttService svc = new MqttService()) {
            svc.connect("tcp://localhost:1883", "nexus-it-v5", null, null, true, null, null, 0);
            assertTrue(svc.isConnected());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<MqttService.Incoming> got = new AtomicReference<>();
            svc.setListener(new MqttService.MessageListener() {
                @Override public void onMessage(MqttService.Incoming m) { got.set(m); latch.countDown(); }
                @Override public void onConnectionLost(Throwable cause) { }
            });

            svc.subscribe(topic, 1);
            MqttMessageProperties props = MqttMessageProperties.builder()
                    .withUserProperty("trace-id", "nexus-42")
                    .withContentType("application/json")
                    .withMessageExpiryInterval(120L)
                    .withResponseTopic(topic + "/reply")
                    .withCorrelationData("corr-1");
            svc.publish(topic, "{\"v5\":true}", 1, false, props);

            assertTrue(latch.await(15, TimeUnit.SECONDS), "v5 message not received within 15s");
            MqttMessageProperties received = got.get().properties();
            assertEquals("application/json", received.contentType());
            assertEquals(120L, received.messageExpiryInterval());
            assertEquals(topic + "/reply", received.responseTopic());
            assertArrayEquals("corr-1".getBytes(), received.correlationData());
            assertEquals(new MqttMessageProperties.UserProperty("trace-id", "nexus-42"),
                    received.userProperties().get(0));
        }
    }
}
