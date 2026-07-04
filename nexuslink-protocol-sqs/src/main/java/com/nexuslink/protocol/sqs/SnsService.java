package com.nexuslink.protocol.sqs;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.sns.model.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * A thin AWS SNS client wrapper: create/list topics, publish, and list subscriptions. Same AWS SDK v2
 * + url-connection HTTP client and emulator-awareness as {@link SqsService} — point {@code endpoint}
 * at LocalStack for offline testing, or leave it blank for real AWS.
 */
public final class SnsService implements AutoCloseable {

    /** One topic subscription: its ARN, protocol (sqs/http/…) and endpoint. */
    public record Subscription(String arn, String protocol, String endpoint) {}

    private SnsClient client;

    public void connect(String endpoint, String region, String accessKey, String secretKey) {
        close();
        SnsClientBuilder b = SnsClient.builder()
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                accessKey == null || accessKey.isBlank() ? "test" : accessKey,
                                secretKey == null || secretKey.isBlank() ? "test" : secretKey)));
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint.trim()));
        client = b.build();
    }

    public boolean isConnected() { return client != null; }

    /** Creates a topic (idempotent) and returns its ARN. */
    public String createTopic(String name) {
        return client.createTopic(CreateTopicRequest.builder().name(name).build()).topicArn();
    }

    /** Lists all topic ARNs. */
    public List<String> listTopics() {
        List<String> arns = new ArrayList<>();
        for (Topic t : client.listTopics().topics()) arns.add(t.topicArn());
        return arns;
    }

    /** Publishes {@code message} (with an optional subject) to a topic; returns the message id. */
    public String publish(String topicArn, String subject, String message) {
        PublishRequest.Builder req = PublishRequest.builder().topicArn(topicArn).message(message);
        if (subject != null && !subject.isBlank()) req.subject(subject);
        return client.publish(req.build()).messageId();
    }

    /** Lists the subscriptions of a topic. */
    public List<Subscription> listSubscriptions(String topicArn) {
        List<Subscription> out = new ArrayList<>();
        for (software.amazon.awssdk.services.sns.model.Subscription s :
                client.listSubscriptionsByTopic(ListSubscriptionsByTopicRequest.builder().topicArn(topicArn).build()).subscriptions()) {
            out.add(new Subscription(s.subscriptionArn(), s.protocol(), s.endpoint()));
        }
        return out;
    }

    /** Deletes a topic by ARN. */
    public void deleteTopic(String topicArn) {
        client.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
    }

    @Override
    public void close() {
        if (client != null) { try { client.close(); } catch (Exception ignored) {} client = null; }
    }
}
