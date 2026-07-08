package com.nexuslink.protocol.mqtt;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MQTT client wrapper over Eclipse Paho (MQTT v5.0). Connect to a broker ({@code tcp://},
 * {@code ssl://}, {@code ws://}), subscribe to topic filters and stream incoming messages to a
 * listener, and publish with a chosen QoS / retained flag. An optional username/password and a
 * Last-Will-and-Testament are supported via the connect options.
 *
 * <p>Because it speaks MQTT 5.0, both publish and received messages carry the v5 PUBLISH properties
 * modelled by {@link MqttMessageProperties} (user properties, message-expiry interval, content type,
 * correlation data and response topic).
 *
 * <p>State stays in memory (no disk persistence) and all I/O is blocking — the UI drives it on a
 * background {@code Task}, mirroring the other protocol services.
 */
public final class MqttService implements AutoCloseable {

    /** A received message, decoupled from Paho types for the UI, with its MQTT 5.0 properties. */
    public record Incoming(String topic, String payload, int qos, boolean retained,
                           MqttMessageProperties properties) {
        /** Back-compat convenience for callers that do not care about v5 properties. */
        public Incoming(String topic, String payload, int qos, boolean retained) {
            this(topic, payload, qos, retained, MqttMessageProperties.EMPTY);
        }
    }

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

        MqttConnectionOptions opts = new MqttConnectionOptions();
        // MQTT 5.0 replaces the v3 "clean session" flag with "clean start".
        opts.setCleanStart(cleanSession);
        opts.setConnectionTimeout(15);
        opts.setKeepAliveInterval(30);
        opts.setAutomaticReconnect(true);
        if (username != null && !username.isBlank()) {
            opts.setUserName(username);
            // v5 carries the password as raw bytes rather than a char[].
            opts.setPassword((password == null ? "" : password).getBytes());
        }
        if (willTopic != null && !willTopic.isBlank()) {
            MqttMessage will = new MqttMessage((willPayload == null ? "" : willPayload).getBytes());
            will.setQos(willQos);
            will.setRetained(false);
            opts.setWill(willTopic, will);
        }

        c.setCallback(new MqttCallback() {
            @Override public void disconnected(MqttDisconnectResponse response) {
                MessageListener l = listener;
                if (l != null) {
                    Throwable cause;
                    if (response != null && response.getException() != null) {
                        cause = response.getException();
                    } else {
                        String reason = (response == null || response.getReasonString() == null)
                                ? "disconnected" : response.getReasonString();
                        cause = new Throwable(reason);
                    }
                    l.onConnectionLost(cause);
                }
            }
            @Override public void mqttErrorOccurred(MqttException exception) { /* async error */ }
            @Override public void messageArrived(String topic, MqttMessage message) {
                MessageListener l = listener;
                if (l != null) {
                    l.onMessage(new Incoming(topic, new String(message.getPayload()),
                            message.getQos(), message.isRetained(),
                            fromPaho(message.getProperties())));
                }
            }
            @Override public void deliveryComplete(IMqttToken token) { /* publish ack */ }
            @Override public void connectComplete(boolean reconnect, String serverURI) { /* (re)connected */ }
            @Override public void authPacketArrived(int reasonCode, MqttProperties properties) { /* AUTH */ }
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

    /** Publishes a plain message with no MQTT 5.0 properties. */
    public void publish(String topic, String payload, int qos, boolean retained) throws MqttException {
        publish(topic, payload, qos, retained, MqttMessageProperties.EMPTY);
    }

    /** Publishes a message carrying the given MQTT 5.0 properties (may be {@code null}/empty). */
    public void publish(String topic, String payload, int qos, boolean retained,
                        MqttMessageProperties properties) throws MqttException {
        MqttMessage msg = new MqttMessage((payload == null ? "" : payload).getBytes());
        msg.setQos(qos);
        msg.setRetained(retained);
        if (properties != null && !properties.isEmpty()) {
            msg.setProperties(toPaho(properties));
        }
        require().publish(topic, msg);
    }

    private MqttClient require() {
        MqttClient c = client;
        if (c == null) throw new IllegalStateException("Not connected to an MQTT broker");
        return c;
    }

    // --- MQTT 5.0 property mapping (package-private, unit-tested without a broker) --------------

    /** Maps our {@link MqttMessageProperties} onto a Paho {@link MqttProperties} for PUBLISH. */
    static MqttProperties toPaho(MqttMessageProperties props) {
        MqttProperties out = new MqttProperties();
        if (props == null) {
            return out;
        }
        if (!props.userProperties().isEmpty()) {
            List<UserProperty> ups = new ArrayList<>(props.userProperties().size());
            for (MqttMessageProperties.UserProperty up : props.userProperties()) {
                ups.add(new UserProperty(up.name(), up.value()));
            }
            out.setUserProperties(ups);
        }
        if (props.messageExpiryInterval() != null) {
            out.setMessageExpiryInterval(props.messageExpiryInterval());
        }
        if (props.contentType() != null) {
            out.setContentType(props.contentType());
        }
        if (props.correlationData() != null) {
            out.setCorrelationData(props.correlationData());
        }
        if (props.responseTopic() != null) {
            out.setResponseTopic(props.responseTopic());
        }
        return out;
    }

    /** Maps a Paho {@link MqttProperties} from a received PUBLISH back onto {@link MqttMessageProperties}. */
    static MqttMessageProperties fromPaho(MqttProperties in) {
        if (in == null) {
            return MqttMessageProperties.EMPTY;
        }
        List<MqttMessageProperties.UserProperty> ups = List.of();
        List<UserProperty> raw = in.getUserProperties();
        if (raw != null && !raw.isEmpty()) {
            ups = new ArrayList<>(raw.size());
            for (UserProperty up : raw) {
                ups.add(new MqttMessageProperties.UserProperty(up.getKey(), up.getValue()));
            }
        }
        return new MqttMessageProperties(
                ups,
                in.getMessageExpiryInterval(),
                in.getContentType(),
                in.getCorrelationData(),
                in.getResponseTopic());
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
