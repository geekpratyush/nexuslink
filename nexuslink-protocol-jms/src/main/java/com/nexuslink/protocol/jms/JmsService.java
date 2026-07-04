package com.nexuslink.protocol.jms;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * A generic JMS client over the ActiveMQ Artemis Jakarta provider: connect to a broker URL, send and
 * receive text messages on a queue, and peek a queue without consuming (a browser). Artemis speaks the
 * JMS wire (core protocol on {@code tcp://host:61616}) and, via AMQP, interoperates with other brokers,
 * so this covers the common "generic JMS" case. Blocking — callers run it off the UI thread.
 *
 * <p>A single {@link Session} (auto-acknowledge) is held for the connection's lifetime; producers,
 * consumers and browsers are created and closed per call.</p>
 */
public final class JmsService implements AutoCloseable {

    /** One browsed/received message: its JMS id and text body. */
    public record JmsMessage(String messageId, String body) {}

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

    /** Sends a text message to {@code queueName}; returns the assigned JMS message id. */
    public String sendText(String queueName, String text) throws JMSException {
        Queue queue = session.createQueue(queueName);
        try (MessageProducer producer = session.createProducer(queue)) {
            TextMessage message = session.createTextMessage(text == null ? "" : text);
            producer.send(message);
            return message.getJMSMessageID();
        }
    }

    /**
     * Receives (and consumes) one message from {@code queueName}, waiting up to {@code timeoutMs}.
     * Returns the text body, or {@code null} if none arrived in time.
     */
    public String receiveText(String queueName, long timeoutMs) throws JMSException {
        Queue queue = session.createQueue(queueName);
        try (MessageConsumer consumer = session.createConsumer(queue)) {
            Message message = consumer.receive(timeoutMs);
            if (message == null) return null;
            return message instanceof TextMessage text ? text.getText() : message.toString();
        }
    }

    /** Peeks up to {@code max} messages on {@code queueName} without consuming them (a JMS browser). */
    public List<JmsMessage> browse(String queueName, int max) throws JMSException {
        List<JmsMessage> out = new ArrayList<>();
        Queue queue = session.createQueue(queueName);
        try (QueueBrowser browser = session.createBrowser(queue)) {
            Enumeration<?> e = browser.getEnumeration();
            while (e.hasMoreElements() && out.size() < max) {
                Message m = (Message) e.nextElement();
                String body = m instanceof TextMessage t ? t.getText() : m.toString();
                out.add(new JmsMessage(m.getJMSMessageID(), body));
            }
        }
        return out;
    }

    @Override
    public void close() {
        if (session != null) { try { session.close(); } catch (JMSException ignored) {} session = null; }
        if (connection != null) { try { connection.close(); } catch (JMSException ignored) {} connection = null; }
    }
}
