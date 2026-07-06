package com.nexuslink.protocol.jms;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic JMS client over the ActiveMQ Artemis Jakarta provider: connect to a broker URL, send and
 * receive messages on a queue (Text, Bytes or Map bodies with custom string properties), and peek a
 * queue without consuming (a browser). Artemis speaks the JMS wire (core protocol on
 * {@code tcp://host:61616}) and, via AMQP, interoperates with other brokers, so this covers the common
 * "generic JMS" case. Blocking — callers run it off the UI thread.
 *
 * <p>A single {@link Session} (auto-acknowledge) is held for the connection's lifetime; producers,
 * consumers and browsers are created and closed per call.</p>
 */
public final class JmsService implements AutoCloseable {

    /** JMS message body kinds this client can send/inspect. */
    public enum MessageType { TEXT, BYTES, MAP }

    /** One browsed/received message: its JMS id, body kind, text rendering, and string properties. */
    public record JmsMessage(String messageId, String type, String body, Map<String, String> properties) {}

    private Connection connection;
    private Session session;

    /** Opens a connection to {@code url} (e.g. {@code tcp://localhost:61616}); user/pass may be blank. */
    public void connect(String url, String user, String password) throws JMSException {
        close();
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(url);
        connection = (user == null || user.isBlank())
                ? factory.createConnection()
                : factory.createConnection(user, password);
        session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        connection.start();
    }

    public boolean isConnected() { return connection != null && session != null; }

    /** Sends a plain text message to {@code queueName}; returns the assigned JMS message id. */
    public String sendText(String queueName, String text) throws JMSException {
        return sendMessage(queueName, MessageType.TEXT, text, Map.of());
    }

    /**
     * Sends a message of the given {@code type} to {@code queueName} with optional custom string
     * properties, returning the assigned JMS message id.
     * <ul>
     *   <li>{@link MessageType#TEXT} — {@code body} is the text.</li>
     *   <li>{@link MessageType#BYTES} — {@code body}'s UTF-8 bytes are written.</li>
     *   <li>{@link MessageType#MAP} — {@code body} is parsed as {@code key=value} lines into map entries.</li>
     * </ul>
     */
    public String sendMessage(String queueName, MessageType type, String body,
                              Map<String, String> properties) throws JMSException {
        Queue queue = session.createQueue(queueName);
        try (MessageProducer producer = session.createProducer(queue)) {
            Message message = buildMessage(type, body);
            if (properties != null) {
                for (Map.Entry<String, String> e : properties.entrySet()) {
                    if (e.getKey() != null && !e.getKey().isBlank()) {
                        message.setStringProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
                    }
                }
            }
            producer.send(message);
            return message.getJMSMessageID();
        }
    }

    private Message buildMessage(MessageType type, String body) throws JMSException {
        String text = body == null ? "" : body;
        return switch (type == null ? MessageType.TEXT : type) {
            case TEXT -> session.createTextMessage(text);
            case BYTES -> {
                BytesMessage bytes = session.createBytesMessage();
                bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
                yield bytes;
            }
            case MAP -> {
                MapMessage map = session.createMapMessage();
                for (String line : text.split("\\r?\\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) map.setString(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
                yield map;
            }
        };
    }

    /**
     * Receives (and consumes) one message from {@code queueName}, waiting up to {@code timeoutMs}.
     * Returns the text body, or {@code null} if none arrived in time.
     */
    public String receiveText(String queueName, long timeoutMs) throws JMSException {
        Queue queue = session.createQueue(queueName);
        try (MessageConsumer consumer = session.createConsumer(queue)) {
            Message message = consumer.receive(timeoutMs);
            return message == null ? null : renderBody(message);
        }
    }

    /**
     * Receives (and consumes) one message with full detail (type + properties), or {@code null} if none
     * arrived within {@code timeoutMs}.
     */
    public JmsMessage receive(String queueName, long timeoutMs) throws JMSException {
        Queue queue = session.createQueue(queueName);
        try (MessageConsumer consumer = session.createConsumer(queue)) {
            Message message = consumer.receive(timeoutMs);
            return message == null ? null : toJmsMessage(message);
        }
    }

    /** Peeks up to {@code max} messages on {@code queueName} without consuming them (a JMS browser). */
    public List<JmsMessage> browse(String queueName, int max) throws JMSException {
        List<JmsMessage> out = new ArrayList<>();
        Queue queue = session.createQueue(queueName);
        try (QueueBrowser browser = session.createBrowser(queue)) {
            Enumeration<?> e = browser.getEnumeration();
            while (e.hasMoreElements() && out.size() < max) {
                out.add(toJmsMessage((Message) e.nextElement()));
            }
        }
        return out;
    }

    private JmsMessage toJmsMessage(Message m) throws JMSException {
        return new JmsMessage(m.getJMSMessageID(), typeOf(m), renderBody(m), stringProperties(m));
    }

    private static String typeOf(Message m) {
        if (m instanceof TextMessage) return "Text";
        if (m instanceof BytesMessage) return "Bytes";
        if (m instanceof MapMessage) return "Map";
        return m.getClass().getSimpleName();
    }

    private static String renderBody(Message m) throws JMSException {
        if (m instanceof TextMessage t) return t.getText();
        if (m instanceof BytesMessage b) {
            long len = b.getBodyLength();
            byte[] data = new byte[(int) Math.min(len, Integer.MAX_VALUE)];
            b.readBytes(data);
            return new String(data, StandardCharsets.UTF_8);
        }
        if (m instanceof MapMessage map) {
            StringBuilder sb = new StringBuilder();
            Enumeration<?> names = map.getMapNames();
            while (names.hasMoreElements()) {
                String name = (String) names.nextElement();
                sb.append(name).append('=').append(map.getObject(name)).append('\n');
            }
            return sb.toString().stripTrailing();
        }
        return m.toString();
    }

    private static Map<String, String> stringProperties(Message m) throws JMSException {
        Map<String, String> props = new LinkedHashMap<>();
        Enumeration<?> names = m.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            props.put(name, String.valueOf(m.getObjectProperty(name)));
        }
        return props;
    }

    @Override
    public void close() {
        if (session != null) { try { session.close(); } catch (JMSException ignored) {} session = null; }
        if (connection != null) { try { connection.close(); } catch (JMSException ignored) {} connection = null; }
    }
}
