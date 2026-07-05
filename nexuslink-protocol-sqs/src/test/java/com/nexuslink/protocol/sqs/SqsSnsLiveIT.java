package com.nexuslink.protocol.sqs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live SQS/SNS round-trips against the local {@code test-env} LocalStack (SERVICES=s3,sqs,sns).
 * <pre>docker compose -f test-env/docker-compose.yml up -d localstack</pre>
 * Run with {@code -Dnexuslink.it=true}. Endpoint: http://localhost:4566 (creds test/test).
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class SqsSnsLiveIT {

    private static final String ENDPOINT = "http://localhost:4566";

    @Test
    void sqsSendReceiveDeleteRoundTrip() {
        String name = "nexus-it-" + System.currentTimeMillis();
        try (SqsService sqs = new SqsService()) {
            sqs.connect(ENDPOINT, "us-east-1", "test", "test");
            assertTrue(sqs.isConnected());

            String url = sqs.createQueue(name);
            assertNotNull(url);
            assertTrue(sqs.listQueues().contains(url));

            String id = sqs.send(url, "hello-sqs");
            assertNotNull(id);

            List<SqsService.Message> got = sqs.receive(url, 5, 5);
            assertEquals(1, got.size());
            assertEquals("hello-sqs", got.get(0).body());

            sqs.delete(url, got.get(0).receiptHandle());
            assertTrue(sqs.receive(url, 5, 1).isEmpty(), "message should be gone after delete");

            sqs.deleteQueue(url);
        }
    }

    @Test
    void fifoQueuePreservesGroupOrder() {
        String name = "nexus-it-" + System.currentTimeMillis() + ".fifo";
        try (SqsService sqs = new SqsService()) {
            sqs.connect(ENDPOINT, "us-east-1", "test", "test");
            String url = sqs.createQueue(name);
            sqs.sendFifo(url, "first", "g1");
            sqs.sendFifo(url, "second", "g1");
            List<SqsService.Message> got = sqs.receive(url, 10, 5);
            assertFalse(got.isEmpty());
            assertEquals("first", got.get(0).body(), "FIFO must deliver in send order");
            sqs.deleteQueue(url);
        }
    }

    @Test
    void redriveMovesMessagesFromDlqToTarget() {
        long t = System.currentTimeMillis();
        String dlqName = "nexus-it-dlq-" + t;
        String targetName = "nexus-it-target-" + t;
        try (SqsService sqs = new SqsService()) {
            sqs.connect(ENDPOINT, "us-east-1", "test", "test");
            String dlq = sqs.createQueue(dlqName);
            String target = sqs.createQueue(targetName);
            sqs.send(dlq, "stuck-1");
            sqs.send(dlq, "stuck-2");

            int moved = sqs.redrive(dlq, target, 10);
            assertEquals(2, moved, "both DLQ messages redriven");
            assertTrue(sqs.receive(dlq, 5, 1).isEmpty(), "DLQ drained");
            assertEquals(2, sqs.receive(target, 10, 5).size(), "messages landed on the target queue");

            sqs.deleteQueue(dlq);
            sqs.deleteQueue(target);
        }
    }

    @Test
    void snsCreateTopicPublishAndList() {
        String name = "nexus-it-topic-" + System.currentTimeMillis();
        try (SnsService sns = new SnsService()) {
            sns.connect(ENDPOINT, "us-east-1", "test", "test");
            assertTrue(sns.isConnected());

            String arn = sns.createTopic(name);
            assertNotNull(arn);
            assertTrue(sns.listTopics().contains(arn));

            String id = sns.publish(arn, "subject", "hello-sns");
            assertNotNull(id);

            sns.deleteTopic(arn);
        }
    }
}
