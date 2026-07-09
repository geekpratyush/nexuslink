package com.nexuslink.protocol.pubsub;

import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.TransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ProjectName;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.ReceivedMessage;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.TopicName;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Cloud Pub/Sub client: create/list/delete topics and subscriptions, publish messages and
 * pull them from a subscription (with acknowledgement).
 *
 * <p>When the {@code PUBSUB_EMULATOR_HOST} environment variable is set (e.g. the local
 * {@code gcloud beta emulators pubsub} container) the client talks to that host over a plaintext gRPC
 * channel with anonymous credentials, so it can be exercised end-to-end offline. Otherwise the gax
 * library defaults apply (Application Default Credentials + the real Pub/Sub endpoint).
 */
public final class PubSubService {

    /** A message pulled from a subscription. */
    public record PulledMessage(String messageId, String data, String orderingKey, int attributes) {}

    private String projectId;

    /** Records the connection's project and verifies reachability by listing its topics. */
    public void connect(String projectId) throws Exception {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("a project id is required");
        this.projectId = projectId.trim();
        listTopics();   // force a round-trip so a bad host/project fails fast at connect time
    }

    public boolean isConnected() { return projectId != null; }

    // ---- topics ----

    public String createTopic(String topic) throws Exception {
        try (Transport t = transport(); TopicAdminClient client = topicAdmin(t)) {
            Topic created = client.createTopic(TopicName.of(projectId, topic));
            return created.getName();
        }
    }

    public List<String> listTopics() throws Exception {
        List<String> names = new ArrayList<>();
        try (Transport t = transport(); TopicAdminClient client = topicAdmin(t)) {
            for (Topic topic : client.listTopics(ProjectName.of(projectId)).iterateAll()) {
                names.add(shortName(topic.getName()));
            }
        }
        return names;
    }

    public void deleteTopic(String topic) throws Exception {
        try (Transport t = transport(); TopicAdminClient client = topicAdmin(t)) {
            client.deleteTopic(TopicName.of(projectId, topic));
        }
    }

    // ---- subscriptions ----

    public String createSubscription(String subscription, String topic, int ackDeadlineSeconds) throws Exception {
        try (Transport t = transport(); SubscriptionAdminClient client = subscriptionAdmin(t)) {
            Subscription created = client.createSubscription(
                    ProjectSubscriptionName.of(projectId, subscription).toString(),
                    TopicName.of(projectId, topic).toString(),
                    PushConfig.getDefaultInstance(),
                    Math.max(10, ackDeadlineSeconds));
            return created.getName();
        }
    }

    public List<String> listSubscriptions() throws Exception {
        List<String> names = new ArrayList<>();
        try (Transport t = transport(); SubscriptionAdminClient client = subscriptionAdmin(t)) {
            for (Subscription sub : client.listSubscriptions(ProjectName.of(projectId)).iterateAll()) {
                names.add(shortName(sub.getName()));
            }
        }
        return names;
    }

    public void deleteSubscription(String subscription) throws Exception {
        try (Transport t = transport(); SubscriptionAdminClient client = subscriptionAdmin(t)) {
            client.deleteSubscription(ProjectSubscriptionName.of(projectId, subscription).toString());
        }
    }

    // ---- publish / pull ----

    /** Publishes {@code data} to {@code topic} and returns the server-assigned message id. */
    public String publish(String topic, String data) throws Exception {
        Transport t = transport();
        Publisher.Builder builder = Publisher.newBuilder(TopicName.of(projectId, topic));
        if (t.provider != null) builder.setChannelProvider(t.provider).setCredentialsProvider(t.creds);
        Publisher publisher = builder.build();
        try {
            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8(data == null ? "" : data)).build();
            return publisher.publish(message).get();
        } finally {
            publisher.shutdown();
            t.close();
        }
    }

    /**
     * Synchronously pulls up to {@code maxMessages} from {@code subscription} and acknowledges each one
     * received, so a subsequent pull sees the next batch. Returns an empty list when nothing is pending.
     */
    public List<PulledMessage> pull(String subscription, int maxMessages) throws Exception {
        List<PulledMessage> out = new ArrayList<>();
        try (Transport t = transport()) {
            SubscriberStubSettings.Builder settings = SubscriberStubSettings.newBuilder();
            if (t.provider != null) settings.setTransportChannelProvider(t.provider).setCredentialsProvider(t.creds);
            try (SubscriberStub subscriber = GrpcSubscriberStub.create(settings.build())) {
                String subPath = ProjectSubscriptionName.of(projectId, subscription).toString();
                PullResponse response = subscriber.pullCallable().call(PullRequest.newBuilder()
                        .setSubscription(subPath)
                        .setMaxMessages(Math.max(1, maxMessages))
                        .build());
                List<String> ackIds = new ArrayList<>();
                for (ReceivedMessage received : response.getReceivedMessagesList()) {
                    PubsubMessage m = received.getMessage();
                    out.add(new PulledMessage(m.getMessageId(), m.getData().toStringUtf8(),
                            m.getOrderingKey(), m.getAttributesCount()));
                    ackIds.add(received.getAckId());
                }
                if (!ackIds.isEmpty()) {
                    subscriber.acknowledgeCallable().call(AcknowledgeRequest.newBuilder()
                            .setSubscription(subPath).addAllAckIds(ackIds).build());
                }
            }
        }
        return out;
    }

    // ---- transport (emulator-aware) ----

    /**
     * A per-operation transport. On the emulator it owns a plaintext gRPC channel that must be closed;
     * against real Pub/Sub {@code provider}/{@code creds} are null so the gax defaults apply.
     */
    private static final class Transport implements AutoCloseable {
        final TransportChannelProvider provider;
        final CredentialsProvider creds;
        private final ManagedChannel channel;

        Transport(TransportChannelProvider provider, CredentialsProvider creds, ManagedChannel channel) {
            this.provider = provider;
            this.creds = creds;
            this.channel = channel;
        }

        @Override public void close() {
            if (channel != null) channel.shutdownNow();
        }
    }

    private Transport transport() {
        String emulator = System.getenv("PUBSUB_EMULATOR_HOST");
        if (emulator != null && !emulator.isBlank()) {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(emulator.trim()).usePlaintext().build();
            return new Transport(
                    FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)),
                    NoCredentialsProvider.create(), channel);
        }
        return new Transport(null, null, null);
    }

    private TopicAdminClient topicAdmin(Transport t) throws Exception {
        TopicAdminSettings.Builder b = TopicAdminSettings.newBuilder();
        if (t.provider != null) b.setTransportChannelProvider(t.provider).setCredentialsProvider(t.creds);
        return TopicAdminClient.create(b.build());
    }

    private SubscriptionAdminClient subscriptionAdmin(Transport t) throws Exception {
        SubscriptionAdminSettings.Builder b = SubscriptionAdminSettings.newBuilder();
        if (t.provider != null) b.setTransportChannelProvider(t.provider).setCredentialsProvider(t.creds);
        return SubscriptionAdminClient.create(b.build());
    }

    /** {@code projects/p/topics/t} → {@code t}; leaves a bare name unchanged. */
    static String shortName(String fullName) {
        if (fullName == null) return "";
        int slash = fullName.lastIndexOf('/');
        return slash < 0 ? fullName : fullName.substring(slash + 1);
    }
}
