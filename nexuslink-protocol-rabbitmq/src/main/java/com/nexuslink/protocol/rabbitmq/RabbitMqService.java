package com.nexuslink.protocol.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ client wrapper over the official Java {@code amqp-client} (AMQP 0.9.1). Connect to a
 * broker (via an {@code amqp://}/{@code amqps://} URI or a bare {@code host[:port]}), declare
 * exchanges/queues and bindings, publish messages to an exchange + routing key, and stream
 * deliveries from a queue to a listener.
 *
 * <p>A single {@link Connection} and {@link Channel} are kept in memory; all I/O is blocking, so the
 * UI drives it on a background {@code Task}, mirroring the other protocol services. The
 * {@link #factoryFor} helper is pure (no network I/O) and is the unit-tested seam.
 */
public final class RabbitMqService implements AutoCloseable {

    /** A received message, decoupled from amqp-client types for the UI. */
    public record Incoming(String exchange, String routingKey, String body, long deliveryTag) {}

    /** Delivery stream callbacks (invoked off the UI thread, on the amqp-client consumer thread). */
    public interface MessageListener {
        void onMessage(Incoming message);
        void onCancelled(String consumerTag);
    }

    private volatile Connection connection;
    private volatile Channel channel;
    private volatile MessageListener listener;

    /**
     * Builds and configures a {@link ConnectionFactory} from a connection string — pure, performs no
     * network I/O, so it is fully unit-testable. {@code target} may be a full {@code amqp://} /
     * {@code amqps://} URI (credentials in the URI win) or a bare {@code host} / {@code host:port};
     * {@code username}/{@code password} are applied when non-blank and not already set by a URI.
     */
    public static ConnectionFactory factoryFor(String target, String username, String password)
            throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        String t = target == null ? "" : target.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Enter a broker URI or host");
        }
        boolean uriCarriedCredentials = false;
        if (t.contains("://")) {
            factory.setUri(t);
            uriCarriedCredentials = t.contains("@");
        } else {
            int colon = t.indexOf(':');
            if (colon >= 0) {
                factory.setHost(t.substring(0, colon));
                String portText = t.substring(colon + 1).trim();
                if (!portText.isEmpty()) factory.setPort(Integer.parseInt(portText));
            } else {
                factory.setHost(t);
            }
        }
        if (!uriCarriedCredentials) {
            if (username != null && !username.isBlank()) factory.setUsername(username);
            if (password != null && !password.isBlank()) factory.setPassword(password);
        }
        factory.setConnectionTimeout(15_000);
        factory.setAutomaticRecoveryEnabled(true);
        return factory;
    }

    /** Opens a connection + channel to {@code target}. {@code username}/{@code password} may be blank. */
    public void connect(String target, String username, String password) throws Exception {
        close();
        ConnectionFactory factory = factoryFor(target, username, password);
        Connection c = factory.newConnection();
        Channel ch = c.createChannel();
        this.connection = c;
        this.channel = ch;
    }

    public boolean isConnected() {
        Connection c = connection;
        Channel ch = channel;
        return c != null && c.isOpen() && ch != null && ch.isOpen();
    }

    /** Registers the listener that receives deliveries for any active consumer. */
    public void setListener(MessageListener listener) {
        this.listener = listener;
    }

    /** Declares a (optionally durable) queue, creating it if absent. */
    public void declareQueue(String queue, boolean durable) throws IOException {
        require().queueDeclare(queue, durable, false, false, null);
    }

    /** Declares an exchange of the given AMQP type ({@code direct}, {@code fanout}, {@code topic}, {@code headers}). */
    public void declareExchange(String exchange, String type, boolean durable) throws IOException {
        require().exchangeDeclare(exchange, BuiltinExchangeType.valueOf(type.trim().toUpperCase()), durable);
    }

    /** Binds {@code queue} to {@code exchange} with {@code routingKey}. */
    public void bind(String queue, String exchange, String routingKey) throws IOException {
        require().queueBind(queue, exchange, routingKey == null ? "" : routingKey);
    }

    /**
     * Publishes {@code body} to {@code exchange} with {@code routingKey}. Pass an empty exchange to
     * publish to the default exchange (routing key = queue name). Messages are persistent.
     */
    public void publish(String exchange, String routingKey, String body) throws IOException {
        AMQP.BasicProperties props = MessageProperties.PERSISTENT_TEXT_PLAIN;
        require().basicPublish(exchange == null ? "" : exchange, routingKey == null ? "" : routingKey,
                props, (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Starts consuming from {@code queue}, routing each delivery to the registered listener.
     * Returns the consumer tag (pass to {@link #cancel}). With {@code autoAck} false, deliveries are
     * acknowledged automatically once handed to the listener.
     */
    public String consume(String queue, boolean autoAck) throws IOException {
        Channel ch = require();
        DeliverCallback onDeliver = (consumerTag, delivery) -> {
            MessageListener l = listener;
            if (l != null) {
                l.onMessage(new Incoming(
                        delivery.getEnvelope().getExchange(),
                        delivery.getEnvelope().getRoutingKey(),
                        new String(delivery.getBody(), StandardCharsets.UTF_8),
                        delivery.getEnvelope().getDeliveryTag()));
            }
            if (!autoAck) ch.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        };
        return ch.basicConsume(queue, autoAck, onDeliver, consumerTag -> {
            MessageListener l = listener;
            if (l != null) l.onCancelled(consumerTag);
        });
    }

    /** Cancels an active consumer by tag (best-effort). */
    public void cancel(String consumerTag) throws IOException {
        if (consumerTag != null && !consumerTag.isBlank()) require().basicCancel(consumerTag);
    }

    private Channel require() {
        Channel ch = channel;
        if (ch == null || !ch.isOpen()) throw new IllegalStateException("Not connected to a RabbitMQ broker");
        return ch;
    }

    @Override
    public void close() {
        Channel ch = channel;
        Connection c = connection;
        channel = null;
        connection = null;
        if (ch != null) {
            try {
                if (ch.isOpen()) ch.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        if (c != null) {
            try {
                if (c.isOpen()) c.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }
}
