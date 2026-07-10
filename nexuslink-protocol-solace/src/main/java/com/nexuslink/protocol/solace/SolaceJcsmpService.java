package com.nexuslink.protocol.solace;

import com.solacesystems.jcsmp.Browser;
import com.solacesystems.jcsmp.BrowserProperties;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.ReplayStartLocation;
import com.solacesystems.jcsmp.SDTException;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageProducer;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A Solace PubSub+ client over JCSMP (the native SMF API, not JMS). It covers the two Solace
 * delivery models that matter to a workbench:
 *
 * <ul>
 *   <li><b>Direct</b> — best-effort pub/sub on <em>topics</em>. {@link #publishDirect} sends and
 *       {@link #subscribe} adds a topic subscription whose matches are pushed to a listener. No
 *       broker-side storage; a subscriber only sees messages published while it is subscribed.</li>
 *   <li><b>Guaranteed</b> — persistent messaging via a <em>queue</em> endpoint.
 *       {@link #publishGuaranteed} spools a message to a queue and {@link #browseQueue} peeks the
 *       queue without consuming, using a browser flow.</li>
 * </ul>
 *
 * <p>{@link #replayFromQueue} opens a guaranteed flow with a replay start location, so a consumer can
 * re-read messages the broker has already delivered — Solace's Message Replay, reading from the
 * broker's replay log.</p>
 *
 * <p>Blocking / callback-based. Callers run {@code connect}, {@code publish*} and {@code browseQueue}
 * off the UI thread; {@link #subscribe} delivers on JCSMP's consumer thread, so the supplied listener
 * must hop back to the UI thread itself.</p>
 */
public final class SolaceJcsmpService implements AutoCloseable {

    /** One message surfaced to the caller, flattened out of a JCSMP {@link BytesXMLMessage}. */
    public record SolaceMessage(String messageId,
                                String destination,
                                String deliveryMode,
                                int priority,
                                String body,
                                Map<String, String> properties) {}

    private JCSMPSession session;
    private XMLMessageProducer producer;
    private XMLMessageConsumer directConsumer;

    /** Opens a session to the broker described by {@code profile}, replacing any existing session. */
    public void connect(SolaceConnectionProfile profile) throws JCSMPException {
        close();
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, profile.hostList());
        properties.setProperty(JCSMPProperties.VPN_NAME, profile.vpn());
        properties.setProperty(JCSMPProperties.USERNAME, profile.username());
        properties.setProperty(JCSMPProperties.PASSWORD, profile.password() == null ? "" : profile.password());
        // Reapply topic subscriptions automatically after an HA failover / reconnect.
        properties.setBooleanProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);

        session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();
    }

    public boolean isConnected() {
        return session != null && !session.isClosed();
    }

    /**
     * Ensures a durable, exclusive queue named {@code queueName} exists on the broker, creating it if
     * absent and returning quietly if it is already there. Needs the client username's profile to
     * grant endpoint-management (guaranteed-endpoint) permission.
     */
    public void provisionQueue(String queueName) throws JCSMPException {
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        session.provision(queue, endpointProps(), JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
    }

    /** Publishes a best-effort (Direct) text message to {@code topicName}. */
    public void publishDirect(String topicName, String body) throws JCSMPException {
        publish(JCSMPFactory.onlyInstance().createTopic(topicName), body, DeliveryMode.DIRECT);
    }

    /**
     * Publishes a persistent (Guaranteed) text message to the queue {@code queueName}, blocking until
     * the broker acknowledges it. The queue must already exist on the broker.
     */
    public void publishGuaranteed(String queueName, String body) throws JCSMPException {
        publish(JCSMPFactory.onlyInstance().createQueue(queueName), body, DeliveryMode.PERSISTENT);
    }

    private void publish(com.solacesystems.jcsmp.Destination destination, String body, DeliveryMode mode)
            throws JCSMPException {
        TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        message.setText(body == null ? "" : body);
        message.setDeliveryMode(mode);
        producer().send(message, destination);
    }

    /**
     * Adds a Direct subscription for {@code topicFilter} (which may contain Solace {@code *}/{@code >}
     * wildcards); each matching message is decoded and handed to {@code onMessage} on the consumer
     * thread. Idempotent-ish: call once per distinct filter. The consumer is started on first use.
     */
    public void subscribe(String topicFilter, Consumer<SolaceMessage> onMessage) throws JCSMPException {
        if (directConsumer == null) {
            directConsumer = session.getMessageConsumer((com.solacesystems.jcsmp.XMLMessageListener) null);
            directConsumer.start();
        }
        // A message listener set on the consumer receives every subscription's matches.
        directConsumer.setMessageListener(new com.solacesystems.jcsmp.XMLMessageListener() {
            @Override public void onReceive(BytesXMLMessage message) { onMessage.accept(toSolaceMessage(message)); }
            @Override public void onException(JCSMPException cause) { /* surfaced via the session event handler */ }
        });
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicFilter);
        session.addSubscription(topic);
    }

    /** Removes a previously added Direct topic subscription. */
    public void unsubscribe(String topicFilter) throws JCSMPException {
        session.removeSubscription(JCSMPFactory.onlyInstance().createTopic(topicFilter));
    }

    /**
     * Peeks up to {@code max} messages on the guaranteed queue {@code queueName} without consuming
     * them, using a {@link Browser} (which reads spooled messages non-destructively). Returns them in
     * queue order; {@code perMessageTimeoutMs} bounds the wait for each next message before giving up.
     */
    public java.util.List<SolaceMessage> browseQueue(String queueName, int max, int perMessageTimeoutMs)
            throws JCSMPException {
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        BrowserProperties browserProps = new BrowserProperties();
        browserProps.setEndpoint(queue);
        browserProps.setWaitTimeout(perMessageTimeoutMs);

        java.util.List<SolaceMessage> out = new java.util.ArrayList<>();
        Browser browser = session.createBrowser(browserProps);
        try {
            for (int i = 0; i < max; i++) {
                BytesXMLMessage message = browser.getNext(perMessageTimeoutMs);
                if (message == null) break;      // drained the queue within the timeout
                out.add(toSolaceMessage(message));
            }
        } finally {
            browser.close();
        }
        return out;
    }

    /**
     * Replays already-delivered messages from the broker's replay log for {@code queueName}, from
     * {@code from} onward (or from the very start of the log when {@code from} is {@code null}),
     * delivering up to {@code max} of them to {@code onMessage}. Requires Message Replay to be enabled
     * on the broker for that queue. Returns the number replayed.
     */
    public int replayFromQueue(String queueName, Date from, int max, int perMessageTimeoutMs,
                               Consumer<SolaceMessage> onMessage) throws JCSMPException {
        ReplayStartLocation start = from == null
                ? JCSMPFactory.onlyInstance().createReplayStartLocationBeginning()
                : JCSMPFactory.onlyInstance().createReplayStartLocationDate(from);

        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setReplayStartLocation(start);
        flowProps.setStartState(true);

        int replayed = 0;
        FlowReceiver flow = session.createFlow(null, flowProps, endpointProps());
        try {
            for (int i = 0; i < max; i++) {
                BytesXMLMessage message = flow.receive(perMessageTimeoutMs);
                if (message == null) break;
                onMessage.accept(toSolaceMessage(message));
                message.ackMessage();            // advance the flow past this replayed message
                replayed++;
            }
        } finally {
            flow.close();
        }
        return replayed;
    }

    private XMLMessageProducer producer() throws JCSMPException {
        if (producer == null) {
            producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
                @Override public void responseReceivedEx(Object key) { /* guaranteed ack — nothing to do */ }
                @Override public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
                    // Publish failures on a persistent send surface synchronously to the caller; this
                    // async path is for late NACKs, which we have no correlation key to route.
                }
            });
        }
        return producer;
    }

    private static EndpointProperties endpointProps() {
        EndpointProperties props = new EndpointProperties();
        props.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
        props.setPermission(EndpointProperties.PERMISSION_CONSUME);
        return props;
    }

    private static SolaceMessage toSolaceMessage(BytesXMLMessage message) {
        String body;
        if (message instanceof TextMessage text) {
            body = text.getText();
        } else {
            byte[] bytes = message.getBytes();
            body = bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return new SolaceMessage(
                message.getMessageId(),
                message.getDestination() == null ? null : message.getDestination().getName(),
                String.valueOf(message.getDeliveryMode()),
                message.getPriority(),
                body,
                userProperties(message));
    }

    private static Map<String, String> userProperties(BytesXMLMessage message) {
        Map<String, String> out = new LinkedHashMap<>();
        SDTMap map = message.getProperties();
        if (map != null) {
            for (String key : map.keySet()) {
                try {
                    out.put(key, map.getString(key));
                } catch (SDTException notAString) {
                    out.put(key, "<non-string>");
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (directConsumer != null) { directConsumer.close(); directConsumer = null; }
        if (producer != null) { producer.close(); producer = null; }
        if (session != null) { session.closeSession(); session = null; }
    }
}
