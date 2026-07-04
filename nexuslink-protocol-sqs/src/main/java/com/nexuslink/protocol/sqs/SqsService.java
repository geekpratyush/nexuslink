package com.nexuslink.protocol.sqs;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A thin AWS SQS client wrapper: list/create queues, send, receive (long-poll), delete and purge.
 * Built on the AWS SDK v2 with the lightweight url-connection HTTP client (no Netty), mirroring the
 * S3 module. Emulator-aware: pass a non-blank {@code endpoint} (e.g. {@code http://localhost:4566})
 * to target LocalStack or another S3/SQS-compatible endpoint; leave it blank for real AWS. Callers
 * run these blocking calls off the UI thread.
 */
public final class SqsService implements AutoCloseable {

    /** One received message: its id, body and the receipt handle needed to delete it. */
    public record Message(String messageId, String body, String receiptHandle) {}

    private SqsClient client;

    /** Opens a client for {@code region}; {@code endpoint} overrides the AWS host (blank = real AWS). */
    public void connect(String endpoint, String region, String accessKey, String secretKey) {
        close();
        SqsClientBuilder b = SqsClient.builder()
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

    /** Lists all queue URLs visible to the account. */
    public List<String> listQueues() {
        return client.listQueues(ListQueuesRequest.builder().build()).queueUrls();
    }

    /** Creates a queue and returns its URL. Appends {@code .fifo} attributes when the name ends in {@code .fifo}. */
    public String createQueue(String name) {
        CreateQueueRequest.Builder req = CreateQueueRequest.builder().queueName(name);
        if (name != null && name.endsWith(".fifo")) {
            req.attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true",
                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true"));
        }
        return client.createQueue(req.build()).queueUrl();
    }

    /** Resolves a queue URL by name. */
    public String queueUrl(String name) {
        return client.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build()).queueUrl();
    }

    /** Sends a message to a standard queue and returns its message id. */
    public String send(String queueUrl, String body) {
        return client.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody(body).build()).messageId();
    }

    /** Sends a message to a FIFO queue (a group id orders messages; dedup is content-based). */
    public String sendFifo(String queueUrl, String body, String messageGroupId) {
        return client.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl).messageBody(body).messageGroupId(messageGroupId).build()).messageId();
    }

    /** Receives up to {@code max} messages, long-polling for {@code waitSeconds}. */
    public List<Message> receive(String queueUrl, int max, int waitSeconds) {
        List<Message> out = new ArrayList<>();
        ReceiveMessageResponse resp = client.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(Math.max(1, Math.min(10, max)))
                .waitTimeSeconds(Math.max(0, waitSeconds))
                .build());
        for (software.amazon.awssdk.services.sqs.model.Message m : resp.messages()) {
            out.add(new Message(m.messageId(), m.body(), m.receiptHandle()));
        }
        return out;
    }

    /** Deletes a received message so it is not redelivered. */
    public void delete(String queueUrl, String receiptHandle) {
        client.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl).receiptHandle(receiptHandle).build());
    }

    /** Empties a queue. */
    public void purge(String queueUrl) {
        client.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl).build());
    }

    /** The broker's approximate visible message count for a queue. */
    public int approximateCount(String queueUrl) {
        String v = client.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        .build())
                .attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        try { return v == null ? 0 : Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    /** Deletes a queue by URL. */
    public void deleteQueue(String queueUrl) {
        client.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    @Override
    public void close() {
        if (client != null) { try { client.close(); } catch (Exception ignored) {} client = null; }
    }
}
