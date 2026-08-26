package com.nexuslink.protocol.kafka;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The new Kafka management surface against the real broker in {@code test-env}: producing with
 * headers, partitions and tombstones; reading them back; topic config editing; the cluster panel;
 * consumer-group administration; and seek/replay.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class KafkaAdminLiveIT {

    private static final String BOOTSTRAP = "localhost:9092";

    private KafkaService service;
    private String topic;
    private String target;

    @BeforeEach
    void setUp() throws Exception {
        service = new KafkaService();
        service.connect(BOOTSTRAP, Map.of());
        topic = "nexuslink-it-" + System.nanoTime();
        target = topic + "-replay";
        service.createTopic(topic, 2, (short) 1);
        service.createTopic(target, 1, (short) 1);
    }

    @AfterEach
    void tearDown() {
        try { service.deleteTopic(topic); } catch (Exception ignored) { }
        try { service.deleteTopic(target); } catch (Exception ignored) { }
        service.close();
    }

    @Test
    void headersPartitionAndTimestampAllReachTheBroker() throws Exception {
        KafkaService.SendResult result = service.send(ProduceSpec.of(topic, "k1", "hello")
                .withHeaders(List.of(new ProduceSpec.Header("trace-id", "abc-123"),
                        new ProduceSpec.Header("content-type", "text/plain")))
                .withPartition(1)
                .withTimestamp(1_700_000_000_000L));
        assertEquals(1, result.partition(), "the record went to the partition it was aimed at");

        List<KafkaService.KafkaMessage> read = service.browse(topic, 10, true);
        assertEquals(1, read.size());
        KafkaService.KafkaMessage message = read.get(0);
        assertEquals("hello", message.value());
        assertEquals(1_700_000_000_000L, message.timestamp());
        assertEquals(2, message.headers().size());
        assertEquals("trace-id", message.headers().get(0).name());
        assertEquals("abc-123", message.headers().get(0).value());
        assertTrue(message.headerText().contains("content-type: text/plain"), message.headerText());
    }

    @Test
    void aTombstoneRoundTripsAsANullValueNotAnEmptyString() throws Exception {
        service.send(ProduceSpec.of(topic, "k", "before"));
        service.send(ProduceSpec.tombstone(topic, "k"));

        List<KafkaService.KafkaMessage> read = service.browse(topic, 10, true);
        assertEquals(2, read.size());
        KafkaService.KafkaMessage tombstone = read.stream().filter(KafkaService.KafkaMessage::isTombstone)
                .findFirst().orElseThrow(() -> new AssertionError("no tombstone came back: " + read));
        assertNull(tombstone.value());
        assertEquals("k", tombstone.key());
    }

    @Test
    void anInvalidSpecIsRefusedBeforeAnythingIsSent() {
        assertThrows(IllegalArgumentException.class,
                () -> service.send(ProduceSpec.tombstone(topic, "")));
    }

    @Test
    void topicConfigIsReadableWithDefaultsMarkedAndEditable() throws Exception {
        List<KafkaService.ConfigEntryView> before = service.topicConfig(topic);
        assertFalse(before.isEmpty());
        assertTrue(before.stream().anyMatch(KafkaService.ConfigEntryView::isDefault),
                "a fresh topic runs mostly on broker defaults, and the panel must show that");

        service.alterTopicConfig(topic, Map.of("retention.ms", "604800000"));
        Map<String, String> after = service.topicConfigMap(topic);
        assertEquals("604800000", after.get("retention.ms"));

        // The diff the UI previews covers only the settings being edited — comparing one desired key
        // against the whole effective config would report every other key as "removed".
        ConfigDiff diff = ConfigDiff.compare(Map.of("retention.ms", "604800000"),
                Map.of("retention.ms", after.get("retention.ms")));
        assertTrue(diff.changesToApply().isEmpty(), diff.entries().toString());
        ConfigDiff changed = ConfigDiff.compare(Map.of("retention.ms", "60000"),
                Map.of("retention.ms", after.get("retention.ms")));
        assertEquals(1, changed.changesToApply().size());
    }

    @Test
    void deletingAConfigEntryRevertsItToTheDefault() throws Exception {
        service.alterTopicConfig(topic, Map.of("retention.ms", "600000"));
        assertEquals("600000", service.topicConfigMap(topic).get("retention.ms"));

        service.alterTopicConfig(topic, java.util.Collections.singletonMap("retention.ms", null));
        KafkaService.ConfigEntryView entry = service.topicConfig(topic).stream()
                .filter(e -> e.name().equals("retention.ms")).findFirst().orElseThrow();
        assertTrue(entry.isDefault(), "deleting the override should hand the setting back to the broker");
    }

    @Test
    void theClusterPanelSeesTheBrokerAndItsController() throws Exception {
        List<KafkaService.BrokerView> brokers = service.describeCluster();
        assertEquals(1, brokers.size());
        assertEquals(1, brokers.get(0).id());
        assertTrue(brokers.get(0).controller(), "the single broker is the controller");
        assertFalse(service.clusterId().isBlank());
    }

    @Test
    void consumerGroupMembershipAndDeletionWork() throws Exception {
        service.send(ProduceSpec.of(topic, "k", "v"));
        String group = "nexuslink-it-group-" + System.nanoTime();

        java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
        service.startConsuming(topic, group, true, new KafkaService.MessageListener() {
            @Override public void onMessage(KafkaService.KafkaMessage message) { got.countDown(); }
            @Override public void onError(Throwable error) { }
        });
        assertTrue(got.await(30, java.util.concurrent.TimeUnit.SECONDS), "the consumer never received");

        assertEquals("Stable", service.consumerGroupState(group));
        List<KafkaService.GroupMember> members = service.consumerGroupMembers(group);
        assertEquals(1, members.size());
        assertFalse(members.get(0).assignment().isBlank());
        assertTrue(members.get(0).assignment().contains(topic), members.get(0).assignment());

        service.stopConsuming();
        assertTrue(service.awaitGroupEmpty(group, 30_000), "the group never emptied after stopping");
        service.deleteGroupOffsets(group, topic);
        service.deleteConsumerGroup(group);
        assertFalse(service.listConsumerGroups().contains(group));
    }

    @Test
    void seekingToAnOffsetAndATimestampBothWork() throws Exception {
        for (int i = 0; i < 6; i++) {
            service.send(ProduceSpec.of(topic, "k" + i, "v" + i).withPartition(0)
                    .withTimestamp(1_700_000_000_000L + i * 1000L));
        }
        List<KafkaService.KafkaMessage> fromOffset = service.browseFrom(topic, 0, 4L, null, 10);
        assertEquals(2, fromOffset.size(), "offsets 4 and 5");
        assertEquals("v4", fromOffset.get(0).value());

        List<KafkaService.KafkaMessage> fromTime =
                service.browseFrom(topic, 0, null, 1_700_000_003_000L, 10);
        assertEquals(3, fromTime.size(), "the records at or after that timestamp");
        assertEquals("v3", fromTime.get(0).value());

        List<KafkaService.KafkaMessage> future =
                service.browseFrom(topic, 0, null, 4_000_000_000_000L, 10);
        assertTrue(future.isEmpty(), "a timestamp past the end returns nothing, not everything");
    }

    @Test
    void replayCarriesKeysHeadersAndTombstonesToAnotherTopic() throws Exception {
        service.send(ProduceSpec.of(topic, "a", "first")
                .withHeaders(List.of(new ProduceSpec.Header("origin", "it-test"))));
        service.send(ProduceSpec.tombstone(topic, "b"));

        List<KafkaService.KafkaMessage> source = service.browse(topic, 10, true);
        assertEquals(2, source.size());

        assertEquals(2, service.replay(source, target, true));
        List<KafkaService.KafkaMessage> replayed = service.browse(target, 10, true);
        assertEquals(2, replayed.size());

        KafkaService.KafkaMessage first = replayed.stream()
                .filter(m -> "a".equals(m.key())).findFirst().orElseThrow();
        assertEquals("first", first.value());
        assertEquals("origin", first.headers().get(0).name());
        assertTrue(replayed.stream().anyMatch(KafkaService.KafkaMessage::isTombstone),
                "the tombstone stayed a tombstone");
    }
}
