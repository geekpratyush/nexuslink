package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProduceSpecTest {

    @Test
    void aPlainRecordNeedsOnlyATopic() {
        ProduceSpec spec = ProduceSpec.of("orders", "k", "v");
        assertTrue(spec.isValid());
        assertFalse(spec.tombstone());
        assertNull(spec.partition());
        assertTrue(spec.headers().isEmpty());
    }

    @Test
    void aTombstoneHasANullValueNotAnEmptyOne() {
        ProduceSpec spec = ProduceSpec.tombstone("orders", "customer-7");
        assertTrue(spec.tombstone());
        assertNull(spec.value(), "an empty string would not delete the key; only null does");
        assertTrue(spec.isValid());
    }

    @Test
    void aTombstoneWithoutAKeyIsRefusedBecauseItWouldDeleteNothing() {
        ProduceSpec spec = ProduceSpec.tombstone("orders", "");
        assertFalse(spec.isValid());
        assertTrue(spec.validate().contains("needs a key"), spec.validate());
    }

    @Test
    void markingARecordAsATombstoneDropsAnyValueItHad() {
        ProduceSpec spec = new ProduceSpec("orders", "k", "leftover", true, List.of(), null, null);
        assertNull(spec.value());
    }

    @Test
    void aMissingTopicIsRefused() {
        assertFalse(ProduceSpec.of("", "k", "v").isValid());
        assertEquals("Choose a topic", ProduceSpec.of(null, "k", "v").validate());
    }

    @Test
    void negativePartitionsAndTimestampsAreRefused() {
        assertFalse(ProduceSpec.of("t", "k", "v").withPartition(-1).isValid());
        assertFalse(ProduceSpec.of("t", "k", "v").withTimestamp(-5L).isValid());
        assertTrue(ProduceSpec.of("t", "k", "v").withPartition(0).isValid());
    }

    @Test
    void aHeaderWithoutANameIsRefused() {
        ProduceSpec spec = ProduceSpec.of("t", "k", "v")
                .withHeaders(List.of(new ProduceSpec.Header("", "x")));
        assertFalse(spec.isValid());
        assertTrue(spec.validate().contains("header needs a name"), spec.validate());
    }

    @Test
    void headersParseFromAndRenderToTheCompactForm() {
        List<ProduceSpec.Header> headers = ProduceSpec.parseHeaders("""
                # routing
                trace-id: abc-123
                content-type: application/json
                not a header line
                """);
        assertEquals(2, headers.size());
        assertEquals("trace-id", headers.get(0).name());
        assertEquals("application/json", headers.get(1).value());
        assertEquals(2, ProduceSpec.parseHeaders(ProduceSpec.renderHeaders(headers)).size());
        assertTrue(ProduceSpec.parseHeaders(null).isEmpty());
    }

    @Test
    void repeatedHeaderNamesAreKeptInTheListButCollapseInTheMap() {
        List<ProduceSpec.Header> headers = ProduceSpec.parseHeaders("k: one\nk: two");
        assertEquals(2, headers.size(), "Kafka allows a header name to repeat");
        assertEquals(1, ProduceSpec.of("t", null, "v").withHeaders(headers).headersAsMap().size());
        assertEquals("two", ProduceSpec.of("t", null, "v").withHeaders(headers).headersAsMap().get("k"));
    }

    @Test
    void theDescriptionCallsOutATombstone() {
        assertTrue(ProduceSpec.tombstone("orders", "k").describe().contains("tombstone"));
        assertTrue(ProduceSpec.of("orders", "k", "hello").describe().contains("5 byte value"));
        assertTrue(ProduceSpec.of("orders", "k", "v").withPartition(2).describe().contains("partition 2"));
    }

    @Test
    void aConsumedRecordKnowsWhetherItIsATombstone() {
        KafkaService.KafkaMessage tombstone = new KafkaService.KafkaMessage(0, 1, 0, "k", null, List.of());
        KafkaService.KafkaMessage normal = new KafkaService.KafkaMessage(0, 2, 0, "k", "", List.of());
        assertTrue(tombstone.isTombstone());
        assertFalse(normal.isTombstone(), "an empty value is not a tombstone");
    }

    @Test
    void aConsumedRecordRendersItsHeaders() {
        KafkaService.KafkaMessage message = new KafkaService.KafkaMessage(0, 1, 0, "k", "v",
                List.of(new ProduceSpec.Header("trace-id", "abc")));
        assertEquals("trace-id: abc\n", message.headerText());
        assertTrue(new KafkaService.KafkaMessage(0, 1, 0, "k", "v").headers().isEmpty());
    }
}
