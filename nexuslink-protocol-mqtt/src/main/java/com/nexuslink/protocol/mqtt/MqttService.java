package com.nexuslink.protocol.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.UUID;

/**
 * MQTT client wrapper over Eclipse Paho (v3.1/3.1.1). Connect to a broker
 * ({@code tcp://}, {@code ssl://}, {@code ws://}), subscribe to topic filters and stream
 * incoming messages to a listener, and publish with a chosen QoS / retained flag. An optional
 * username/password and a Last-Will-and-Testament are supported via the connect options.
 *
 * <p>State stays in memory (no disk persistence) and all I/O is blocking — the UI drives it on a
 * background {@code Task}, mirroring the other protocol services.
 */
public final class MqttService implements AutoCloseable {

    /** A received message, decoupled from Paho types for the UI. */
    public record Incoming(String topic, String payload, int qos, boolean retained) {}

    /** Subscription stream callbacks (invoked off the UI thread, on Paho's MQTT thread). */
    public interface MessageListener {
        void onMessage(Incoming message);
        void onConnectionLost(Throwable cause);
    }

    private volatile MqttClient client;
    private volatile MessageListener listener;

    /**
     * Connects to {@code brokerUri} with a generated-or-given client id. {@code username} may be
     * blank for anonymous brokers. {@code willTopic}/{@code willPayload} are optional (blank to skip).
     */
    public void connect(String brokerUri, String clientId, String username, String password,
                        boolean cleanSession, String willTopic, String willPayload, int willQos)
            throws MqttException {
        close();
        String id = (clientId == null || clientId.isBlank())
                ? "nexuslink-" + UUID.randomUUID().toString().substring(0, 8)
                : clientId;
        MqttClient c = new MqttClient(brokerUri, id, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(cleanSession);
        opts.setConnectionTimeout(15);
        opts.setKeepAliveInterval(30);
        opts.setAutomaticReconnect(true);
        if (username != null && !username.isBlank()) {
            opts.setUserName(username);
            opts.setPassword((password == null ? "" : password).toCharArray());
        }
        if (willTopic != null && !willTopic.isBlank()) {
            opts.setWill(willTopic, (willPayload == null ? "" : willPayload).getBytes(), willQos, false);
        }

        c.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) {
                MessageListener l = listener;
                if (l != null) l.onConnectionLost(cause);
            }
            @Override public void messageArrived(String topic, MqttMessage message) {
                MessageListener l = listener;
                if (l != null) {
                    l.onMessage(new Incoming(topic, new String(message.getPayload()),
                            message.getQos(), message.isRetained()));
                }
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { /* publish ack */ }
        });
        c.connect(opts);
        this.client = c;
    }

    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    /** Registers the listener that receives messages for all active subscriptions. */
    public void setListener(MessageListener listener) {
        this.listener = listener;
    }

    public void subscribe(String topicFilter, int qos) throws MqttException {
        require().subscribe(topicFilter, qos);
    }

    public void unsubscribe(String topicFilter) throws MqttException {
        require().unsubscribe(topicFilter);
    }

    public void publish(String topic, String payload, int qos, boolean retained) throws MqttException {
        MqttMessage msg = new MqttMessage((payload == null ? "" : payload).getBytes());
        msg.setQos(qos);
        msg.setRetained(retained);
        require().publish(topic, msg);
    }

    private MqttClient require() {
        MqttClient c = client;
        if (c == null) throw new IllegalStateException("Not connected to an MQTT broker");
        return c;
    }

    @Override
    public void close() {
        MqttClient c = client;
        client = null;
        if (c != null) {
            try {
                if (c.isConnected()) c.disconnect();
            } catch (Exception ignored) {
                // best-effort
            }
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }
}
