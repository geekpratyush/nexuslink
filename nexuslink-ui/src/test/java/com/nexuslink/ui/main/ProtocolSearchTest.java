package com.nexuslink.ui.main;

import com.nexuslink.core.search.QuickFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The words that have to find each connection type in the sidebar's filter box. */
class ProtocolSearchTest {

    /** Ids matching {@code query}, drawn from the labels the sidebar actually shows. */
    private static List<String> hits(String query) {
        return List.of(
                        new String[]{"rest", "New REST Request"},
                        new String[]{"sql", "SQL Client"},
                        new String[]{"mongo", "MongoDB Client"},
                        new String[]{"redis", "Redis"},
                        new String[]{"kafka", "Kafka"},
                        new String[]{"rabbitmq", "RabbitMQ"},
                        new String[]{"ibmmq", "IBM MQ"},
                        new String[]{"sqs", "AWS SQS / SNS"},
                        new String[]{"servicebus", "Azure Service Bus"},
                        new String[]{"s3", "S3 / Object Storage"},
                        new String[]{"gcs", "Google Cloud Storage"},
                        new String[]{"azure", "Azure Blob"},
                        new String[]{"sftp", "SFTP"},
                        new String[]{"ssh", "SSH Terminal"},
                        new String[]{"llm", "AI / LLM Tester"})
                .stream()
                .filter(p -> QuickFilter.matches(query, ProtocolSearch.haystack(p[0], p[1])))
                .map(p -> p[0])
                .toList();
    }

    @Test
    void theEverydayWordForADatabaseFindsTheSqlClient() {
        assertTrue(hits("postgres").contains("sql"));
        assertTrue(hits("oracle").contains("sql"));
        assertTrue(hits("jdbc").contains("sql"));
    }

    @Test
    void queueFindsEveryBrokerAndNothingElse() {
        List<String> queue = hits("queue");
        assertTrue(queue.containsAll(List.of("rabbitmq", "ibmmq", "sqs", "servicebus", "kafka")));
        assertFalse(queue.contains("sql"));
        assertFalse(queue.contains("rest"));
    }

    @Test
    void bucketFindsTheObjectStores() {
        assertEquals(List.of("s3", "gcs", "azure"), hits("bucket"));
    }

    @Test
    void aTypeIsStillFoundByItsOwnLabelAndId() {
        assertTrue(hits("mongodb").contains("mongo"));
        assertTrue(hits("sftp").contains("sftp"));
        assertTrue(hits("terminal").contains("ssh"));
    }

    @Test
    void anEmptyFilterKeepsEveryType() {
        assertEquals(15, hits("").size());
    }

    @Test
    void nonsenseMatchesNothing() {
        assertTrue(hits("zzzz").isEmpty());
    }

    @Test
    void aTypeWithoutExtraWordsHasNoKeywords() {
        assertEquals("", ProtocolSearch.keywords("no-such-protocol"));
        assertFalse(ProtocolSearch.keywords("sql").isBlank());
    }
}
