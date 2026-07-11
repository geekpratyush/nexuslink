package com.nexuslink.protocol.servicebus;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClient;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClientBuilder;
import com.azure.messaging.servicebus.administration.models.QueueProperties;
import com.azure.messaging.servicebus.administration.models.SubscriptionProperties;
import com.azure.messaging.servicebus.administration.models.TopicProperties;
import com.azure.messaging.servicebus.models.SubQueue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Azure Service Bus client: manage queues, topics and subscriptions (via the administration API) and
 * send / peek-lock-receive messages against queues and topic subscriptions, including their
 * dead-letter sub-queues.
 *
 * <p>Connection is by the standard Service Bus connection string. When that string carries
 * {@code UseDevelopmentEmulator=true} it targets the local Service Bus emulator, which speaks the AMQP
 * data plane (send/receive) but <em>not</em> the HTTP administration plane — its queues/topics are
 * pre-provisioned from the emulator's config file, so the {@code list/create/delete} management calls
 * apply only against a real namespace.
 */
public final class ServiceBusService {

    /** A message received (and settled) from a queue, subscription, or dead-letter sub-queue. */
    public record ReceivedMessage(String messageId, String body, String subject,
                                  long sequenceNumber, long deliveryCount) {}

    private static final Duration RECEIVE_WAIT = Duration.ofSeconds(5);

    private String connectionString;
    private ServiceBusConnectionString parsed;
    private ServiceBusAdministrationClient admin;

    /**
     * Parses and records the connection string and builds the (offline) administration client. No
     * network round-trip is forced here so this also works against the emulator, where management is
     * unavailable; a bad host surfaces on the first real send/receive/list instead.
     */
    public void connect(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("a connection string is required");
        }
        this.parsed = ServiceBusConnectionString.parse(connectionString.trim());
        this.connectionString = connectionString.trim();
        this.admin = parsed.isDevelopment() ? null
                : new ServiceBusAdministrationClientBuilder()
                        .connectionString(this.connectionString)
                        .buildClient();
    }

    public boolean isConnected() {
        return connectionString != null;
    }

    /** {@code true} when the target is the local emulator (management operations are unavailable). */
    public boolean isEmulator() {
        return parsed != null && parsed.isDevelopment();
    }

    /** The namespace host of the current connection, or {@code null} when not connected. */
    public String namespace() {
        return parsed == null ? null : parsed.namespace();
    }

    // ---- queues (management plane) ----

    public List<String> listQueues() {
        List<String> names = new ArrayList<>();
        for (QueueProperties q : admin().listQueues()) {
            names.add(q.getName());
        }
        return names;
    }

    public void createQueue(String queue) {
        admin().createQueue(queue);
    }

    public void deleteQueue(String queue) {
        admin().deleteQueue(queue);
    }

    // ---- topics & subscriptions (management plane) ----

    public List<String> listTopics() {
        List<String> names = new ArrayList<>();
        for (TopicProperties t : admin().listTopics()) {
            names.add(t.getName());
        }
        return names;
    }

    public void createTopic(String topic) {
        admin().createTopic(topic);
    }

    public void deleteTopic(String topic) {
        admin().deleteTopic(topic);
    }

    public List<String> listSubscriptions(String topic) {
        List<String> names = new ArrayList<>();
        for (SubscriptionProperties s : admin().listSubscriptions(topic)) {
            names.add(s.getSubscriptionName());
        }
        return names;
    }

    public void createSubscription(String topic, String subscription) {
        admin().createSubscription(topic, subscription);
    }

    public void deleteSubscription(String topic, String subscription) {
        admin().deleteSubscription(topic, subscription);
    }

    // ---- send / receive (data plane) ----

    /** Sends {@code body} to {@code queue} and returns the assigned message id. */
    public String sendToQueue(String queue, String body) {
        try (ServiceBusSenderClient sender = clientBuilder().sender().queueName(queue).buildClient()) {
            return send(sender, body);
        }
    }

    /** Sends {@code body} to {@code topic} and returns the assigned message id. */
    public String sendToTopic(String topic, String body) {
        try (ServiceBusSenderClient sender = clientBuilder().sender().topicName(topic).buildClient()) {
            return send(sender, body);
        }
    }

    private static String send(ServiceBusSenderClient sender, String body) {
        ServiceBusMessage message = new ServiceBusMessage(body == null ? "" : body);
        String id = UUID.randomUUID().toString();
        message.setMessageId(id);
        sender.sendMessage(message);
        return id;
    }

    /**
     * Peek-lock receives up to {@code max} messages from {@code queue} (or its dead-letter sub-queue
     * when {@code deadLetter}) and completes (removes) each one, so a subsequent receive sees the next
     * batch. Returns an empty list when nothing is pending within the receive window.
     */
    public List<ReceivedMessage> receiveFromQueue(String queue, int max, boolean deadLetter) {
        ServiceBusClientBuilder.ServiceBusReceiverClientBuilder builder =
                clientBuilder().receiver().queueName(queue);
        if (deadLetter) {
            builder.subQueue(SubQueue.DEAD_LETTER_QUEUE);
        }
        return drain(builder.buildClient(), max, deadLetter);
    }

    /**
     * Peek-lock receives up to {@code max} messages from {@code topic}/{@code subscription} (or its
     * dead-letter sub-queue when {@code deadLetter}) and completes each one.
     */
    public List<ReceivedMessage> receiveFromSubscription(String topic, String subscription,
                                                         int max, boolean deadLetter) {
        ServiceBusClientBuilder.ServiceBusReceiverClientBuilder builder =
                clientBuilder().receiver().topicName(topic).subscriptionName(subscription);
        if (deadLetter) {
            builder.subQueue(SubQueue.DEAD_LETTER_QUEUE);
        }
        return drain(builder.buildClient(), max, deadLetter);
    }

    private List<ReceivedMessage> drain(ServiceBusReceiverClient receiver, int max, boolean deadLetter) {
        List<ReceivedMessage> out = new ArrayList<>();
        try (receiver) {
            for (ServiceBusReceivedMessage m : receiver.receiveMessages(Math.max(1, max), RECEIVE_WAIT)) {
                String body = m.getBody() == null ? "" : m.getBody().toString();
                out.add(new ReceivedMessage(m.getMessageId(), body, m.getSubject(),
                        m.getSequenceNumber(), m.getDeliveryCount()));
                // On the dead-letter sub-queue the received message is already dead-lettered; completing
                // it removes it, matching the "each received message is settled" contract of the pull.
                receiver.complete(m);
            }
        }
        return out;
    }

    // ---- builders ----

    private ServiceBusClientBuilder clientBuilder() {
        return new ServiceBusClientBuilder().connectionString(require());
    }

    private String require() {
        if (connectionString == null) {
            throw new IllegalStateException("not connected");
        }
        return connectionString;
    }

    private ServiceBusAdministrationClient admin() {
        if (admin == null) {
            if (isEmulator()) {
                throw new IllegalStateException(
                        "The Service Bus emulator has no management API — its entities are pre-provisioned "
                                + "in the emulator config; connect to a real namespace to list/create/delete.");
            }
            throw new IllegalStateException("not connected");
        }
        return admin;
    }
}
