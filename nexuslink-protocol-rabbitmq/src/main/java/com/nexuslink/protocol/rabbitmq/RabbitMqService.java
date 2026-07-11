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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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
    /** The channel that {@code confirmSelect()} has already been enabled on, so it is run once. */
    private volatile Channel confirmsEnabledOn;

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

    /**
     * Declares a queue with extra {@code x-arguments} — e.g. the dead-letter / TTL / max-length map from
     * {@link DeadLetterArgs}. A null or empty map behaves like {@link #declareQueue(String, boolean)}.
     */
    public void declareQueue(String queue, boolean durable, Map<String, Object> args) throws IOException {
        require().queueDeclare(queue, durable, false, false,
                args == null || args.isEmpty() ? null : args);
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
     * Publishes {@code body} with an explicit {@code contentType}, {@code correlationId} and custom
     * {@code headers} (any of which may be null/blank/empty to omit). Messages stay persistent. This is
     * the message-properties-editor path; the pure {@link #buildProperties} builder is the tested seam.
     */
    public void publish(String exchange, String routingKey, String body,
                        String contentType, String correlationId, Map<String, String> headers) throws IOException {
        require().basicPublish(exchange == null ? "" : exchange, routingKey == null ? "" : routingKey,
                buildProperties(contentType, correlationId, headers),
                (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds AMQP {@link AMQP.BasicProperties} for a persistent message with an optional content type,
     * correlation id and string headers. Package-visible + pure (no I/O) so it can be unit-tested.
     */
    static AMQP.BasicProperties buildProperties(String contentType, String correlationId,
                                                Map<String, String> headers) {
        AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder().deliveryMode(2); // persistent
        if (contentType != null && !contentType.isBlank()) b.contentType(contentType.trim());
        if (correlationId != null && !correlationId.isBlank()) b.correlationId(correlationId.trim());
        if (headers != null && !headers.isEmpty()) {
            Map<String, Object> h = new LinkedHashMap<>();
            headers.forEach((k, v) -> { if (k != null && !k.isBlank()) h.put(k.trim(), v); });
            if (!h.isEmpty()) b.headers(h);
        }
        return b.build();
    }

    /**
     * Publishes {@code body} like {@link #publish}, then waits for a publisher confirm from the
     * broker and reports whether it was {@link PublishConfirm#ACKED}, {@link PublishConfirm#NACKED}
     * or {@link PublishConfirm#TIMEOUT}. {@code confirmSelect()} is enabled once per channel (lazily,
     * on first use). A {@code timeoutMs} of {@code 0} waits indefinitely.
     *
     * <p>Requires a live broker for an end-to-end run (the wire round-trip and confirm); the pure
     * boolean-to-outcome mapping it relies on is unit-tested via {@link PublishConfirm}.
     */
    public PublishConfirm publishConfirmed(String exchange, String routingKey, String body, long timeoutMs)
            throws IOException, InterruptedException {
        return publishConfirmed(exchange, routingKey, body, timeoutMs, null, null, null);
    }

    /** {@link #publishConfirmed(String, String, String, long)} with explicit message properties. */
    public PublishConfirm publishConfirmed(String exchange, String routingKey, String body, long timeoutMs,
                                           String contentType, String correlationId, Map<String, String> headers)
            throws IOException, InterruptedException {
        Channel ch = require();
        enableConfirms(ch);
        ch.basicPublish(exchange == null ? "" : exchange, routingKey == null ? "" : routingKey,
                buildProperties(contentType, correlationId, headers),
                (body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        try {
            return PublishConfirm.fromWaitForConfirms(ch.waitForConfirms(timeoutMs));
        } catch (TimeoutException e) {
            return PublishConfirm.TIMEOUT;
        }
    }

    /** Enables {@code confirmSelect()} on {@code ch} at most once (it is irreversible per channel). */
    private void enableConfirms(Channel ch) throws IOException {
        if (confirmsEnabledOn != ch) {
            ch.confirmSelect();
            confirmsEnabledOn = ch;
        }
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

    /**
     * Starts consuming from {@code queue} with auto-ack OFF, handing each delivery (including its
     * {@link Incoming#deliveryTag()}) to the registered listener without acknowledging it. The caller
     * is then responsible for calling {@link #ack} or {@link #nack} for every delivery tag. Returns
     * the consumer tag (pass to {@link #cancel}).
     *
     * <p>Requires a live broker for an end-to-end run.
     */
    public String consumeManual(String queue) throws IOException {
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
        };
        return ch.basicConsume(queue, false, onDeliver, consumerTag -> {
            MessageListener l = listener;
            if (l != null) l.onCancelled(consumerTag);
        });
    }

    /**
     * Acknowledges a single delivery by tag (the broker may then discard the message). Pair with
     * {@link #consumeManual}. Requires a live broker for an end-to-end run.
     */
    public void ack(long deliveryTag) throws IOException {
        require().basicAck(deliveryTag, false);
    }

    /**
     * Negatively acknowledges a single delivery by tag. When {@code requeue} is true the broker
     * re-queues the message for redelivery, otherwise it is dropped (or dead-lettered if configured).
     * Pair with {@link #consumeManual}. Requires a live broker for an end-to-end run.
     */
    public void nack(long deliveryTag, boolean requeue) throws IOException {
        require().basicNack(deliveryTag, false, requeue);
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
        confirmsEnabledOn = null;
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
