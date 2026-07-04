package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.ConsumerLagCalculator.LagRow;
import com.nexuslink.protocol.kafka.ConsumerLagCalculator.TopicPartitionKey;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Kafka client wrapper: an {@link Admin} client for topic discovery, a lazily-created producer for
 * sending, and a background-polling consumer for browsing. Connect with bootstrap servers plus an
 * optional security-property map (security.protocol / sasl.mechanism / sasl.jaas.config / ssl.*),
 * so PLAINTEXT, SSL/mTLS and SASL (PLAIN/SCRAM) brokers all work.
 */
public final class KafkaService implements AutoCloseable {

    private static final String STR = "org.apache.kafka.common.serialization.StringSerializer";
    private static final String STR_DE = "org.apache.kafka.common.serialization.StringDeserializer";

    /** Producer ack: where a record landed. */
    public record SendResult(int partition, long offset, long timestamp) {}

    /** A consumed record, decoupled from Kafka types for the UI. */
    public record KafkaMessage(int partition, long offset, long timestamp, String key, String value) {}

    /** Streaming consumer callbacks (invoked off the UI thread). */
    public interface MessageListener {
        void onMessage(KafkaMessage message);
        void onError(Throwable error);
    }

    private String bootstrap;
    private final Map<String, String> security = new HashMap<>();
    private Admin admin;
    private KafkaProducer<String, String> producer;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile boolean consuming;

    public void connect(String bootstrapServers, Map<String, String> securityProps) throws Exception {
        close();
        this.bootstrap = bootstrapServers;
        this.security.clear();
        if (securityProps != null) this.security.putAll(securityProps);

        Properties props = base();
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "12000");
        this.admin = Admin.create(props);
        // Force a round-trip to verify connectivity/auth.
        admin.listTopics().names().get(12, TimeUnit.SECONDS);
    }

    public boolean isConnected() { return admin != null; }

    public List<String> listTopics() throws Exception {
        List<String> names = new ArrayList<>(admin.listTopics().names().get(12, TimeUnit.SECONDS));
        Collections.sort(names);
        return names;
    }

    public Map<String, TopicDescription> describeAll(List<String> topics) throws Exception {
        return admin.describeTopics(topics).allTopicNames().get(15, TimeUnit.SECONDS);
    }

    /** Creates a topic with the given partition count and replication factor. Needs a live broker. */
    public void createTopic(String name, int partitions, short replicationFactor) throws Exception {
        admin.createTopics(List.of(new NewTopic(name, partitions, replicationFactor)))
                .all().get(15, TimeUnit.SECONDS);
    }

    /** Deletes a topic. Needs a live broker. */
    public void deleteTopic(String name) throws Exception {
        admin.deleteTopics(List.of(name)).all().get(15, TimeUnit.SECONDS);
    }

    public TopicDescription describe(String topic) throws Exception {
        return admin.describeTopics(List.of(topic)).allTopicNames().get(12, TimeUnit.SECONDS).get(topic);
    }

    /** Lists the broker's consumer group ids, sorted. Needs a live broker. */
    public List<String> listConsumerGroups() throws Exception {
        List<String> ids = new ArrayList<>();
        for (ConsumerGroupListing g : admin.listConsumerGroups().all().get(12, TimeUnit.SECONDS)) {
            ids.add(g.groupId());
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * Computes per-partition lag for {@code group} by pairing its committed offsets with each
     * partition's current log-end (latest) offset and feeding them to
     * {@link ConsumerLagCalculator}. Partitions the group has committed but the broker no longer
     * reports an end offset for are skipped. Needs a live broker.
     */
    public List<LagRow> consumerGroupLag(String group) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committedRaw = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(12, TimeUnit.SECONDS);

        Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
        for (TopicPartition tp : committedRaw.keySet()) latestSpecs.put(tp, OffsetSpec.latest());
        Map<TopicPartition, ListOffsetsResultInfo> endRaw = admin.listOffsets(latestSpecs)
                .all().get(15, TimeUnit.SECONDS);

        Map<TopicPartitionKey, Long> committed = new HashMap<>();
        committedRaw.forEach((tp, om) -> committed.put(new TopicPartitionKey(tp.topic(), tp.partition()), om.offset()));
        Map<TopicPartitionKey, Long> endOffsets = new HashMap<>();
        endRaw.forEach((tp, info) -> endOffsets.put(new TopicPartitionKey(tp.topic(), tp.partition()), info.offset()));

        return ConsumerLagCalculator.compute(group, committed, endOffsets);
    }

    /**
     * Builds an offset-reset plan for {@code group} over the partitions it has committed: fetches
     * committed + begin + end offsets (and, for {@link OffsetResetPlanner.Strategy#TIMESTAMP}, the
     * offsets at/after {@code timestampMillis}) and delegates the actual target computation to the pure
     * {@link OffsetResetPlanner}. {@code arg} is the specific offset for SPECIFIC_OFFSET or the signed
     * delta for SHIFT_BY. Nothing is applied — call {@link #applyOffsetReset} with the returned rows.
     * Needs a live broker.
     */
    public List<OffsetResetPlanner.ResetRow> previewOffsetReset(String group,
            OffsetResetPlanner.Strategy strategy, long arg, long timestampMillis) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committedRaw = admin.listConsumerGroupOffsets(group)
                .partitionsToOffsetAndMetadata().get(12, TimeUnit.SECONDS);
        var partitions = committedRaw.keySet();

        Map<TopicPartition, OffsetSpec> earliestSpecs = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
        for (TopicPartition tp : partitions) {
            earliestSpecs.put(tp, OffsetSpec.earliest());
            latestSpecs.put(tp, OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResultInfo> beginRaw = admin.listOffsets(earliestSpecs).all().get(15, TimeUnit.SECONDS);
        Map<TopicPartition, ListOffsetsResultInfo> endRaw = admin.listOffsets(latestSpecs).all().get(15, TimeUnit.SECONDS);

        Map<TopicPartitionKey, Long> committed = new HashMap<>();
        committedRaw.forEach((tp, om) -> committed.put(key(tp), om.offset()));
        Map<TopicPartitionKey, Long> begin = new HashMap<>();
        beginRaw.forEach((tp, info) -> begin.put(key(tp), info.offset()));
        Map<TopicPartitionKey, Long> end = new HashMap<>();
        endRaw.forEach((tp, info) -> end.put(key(tp), info.offset()));

        Map<TopicPartitionKey, Long> tsOffsets = null;
        if (strategy == OffsetResetPlanner.Strategy.TIMESTAMP) {
            Map<TopicPartition, OffsetSpec> tsSpecs = new HashMap<>();
            for (TopicPartition tp : partitions) tsSpecs.put(tp, OffsetSpec.forTimestamp(timestampMillis));
            Map<TopicPartition, ListOffsetsResultInfo> tsRaw = admin.listOffsets(tsSpecs).all().get(15, TimeUnit.SECONDS);
            tsOffsets = new HashMap<>();
            for (var e : tsRaw.entrySet()) {
                if (e.getValue().offset() >= 0) tsOffsets.put(key(e.getKey()), e.getValue().offset());
                // offset < 0 → no message at/after the timestamp; leave absent so the planner falls back to end
            }
        }
        return OffsetResetPlanner.plan(strategy, arg, committed, begin, end, tsOffsets);
    }

    /**
     * Commits the planned target offsets for {@code group} via {@code alterConsumerGroupOffsets}. The
     * broker only allows this while the group has no active members (an empty/inactive group), so a
     * live group must be stopped first — otherwise the call fails and the error is surfaced to the UI.
     * Needs a live broker.
     */
    public void applyOffsetReset(String group, List<OffsetResetPlanner.ResetRow> rows) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> targets = new HashMap<>();
        for (OffsetResetPlanner.ResetRow r : rows) {
            targets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.target()));
        }
        admin.alterConsumerGroupOffsets(group, targets).all().get(15, TimeUnit.SECONDS);
    }

    private static TopicPartitionKey key(TopicPartition tp) {
        return new TopicPartitionKey(tp.topic(), tp.partition());
    }

    /**
     * Flattens the AdminClient's client-level metrics to a plain {@code name → value} map (numeric
     * metrics only), the Kafka-type-free input {@link KafkaMetricsSummary} curates for display. When a
     * metric name appears under several node scopes the first numeric value wins. Needs a live broker.
     */
    public Map<String, Double> metricValues() {
        Map<String, Double> out = new HashMap<>();
        admin.metrics().forEach((name, metric) -> {
            Object v = metric.metricValue();
            if (v instanceof Number n) out.putIfAbsent(name.name(), n.doubleValue());
        });
        return out;
    }

    /** Sends one record (synchronously) and returns where it landed. */
    public SendResult send(String topic, String key, String value) throws Exception {
        RecordMetadata md = producer().send(new ProducerRecord<>(topic,
                key == null || key.isBlank() ? null : key, value)).get(15, TimeUnit.SECONDS);
        return new SendResult(md.partition(), md.offset(), md.timestamp());
    }

    /**
     * Browses up to {@code maxMessages} from {@code topic} with <em>no consumer-group side effects</em>:
     * the consumer has no {@code group.id}, uses manual {@code assign} (never {@code subscribe}) and
     * {@code enable.auto.commit=false}, and seeks to the beginning or end of every partition. So it never
     * joins a group, never commits offsets and never rebalances anyone — a safe read-only peek. Returns
     * once {@code maxMessages} are collected or a few polls come back empty. Blocking; call off the UI
     * thread. Needs a live broker.
     */
    public List<KafkaMessage> browse(String topic, int maxMessages, boolean fromBeginning) {
        Properties props = base();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, STR_DE);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, STR_DE);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");   // never commit
        // Deliberately NO group.id → the consumer joins no group and triggers no rebalance.

        List<KafkaMessage> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            List<PartitionInfo> parts = c.partitionsFor(topic, Duration.ofSeconds(10));
            if (parts == null || parts.isEmpty()) return out;
            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo p : parts) tps.add(new TopicPartition(topic, p.partition()));
            c.assign(tps);
            if (fromBeginning) c.seekToBeginning(tps); else c.seekToEnd(tps);

            int emptyPolls = 0;
            while (out.size() < maxMessages && emptyPolls < 3) {
                ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));
                if (records.isEmpty()) { emptyPolls++; continue; }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> r : records) {
                    out.add(new KafkaMessage(r.partition(), r.offset(), r.timestamp(), r.key(), r.value()));
                    if (out.size() >= maxMessages) break;
                }
            }
        }
        return out;
    }

    /** Starts polling {@code topic} on a background thread; messages flow to {@code listener}. */
    public void startConsuming(String topic, String group, boolean fromBeginning, MessageListener listener) {
        stopConsuming();
        Properties props = base();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group == null || group.isBlank() ? "nexuslink-" + System.currentTimeMillis() : group);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, STR_DE);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, STR_DE);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, fromBeginning ? "earliest" : "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        consuming = true;
        Thread t = new Thread(() -> pollLoop(listener), "kafka-consumer");
        t.setDaemon(true);
        t.start();
    }

    private void pollLoop(MessageListener listener) {
        try {
            while (consuming) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    listener.onMessage(new KafkaMessage(r.partition(), r.offset(), r.timestamp(), r.key(), r.value()));
                }
            }
        } catch (WakeupException ignored) {
            // expected on stop
        } catch (Exception e) {
            if (consuming) listener.onError(e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) { }
        }
    }

    public void stopConsuming() {
        consuming = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) c.wakeup();
        consumer = null;
    }

    private synchronized KafkaProducer<String, String> producer() {
        if (producer == null) {
            Properties props = base();
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, STR);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, STR);
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            producer = new KafkaProducer<>(props);
        }
        return producer;
    }

    private Properties base() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        security.forEach((k, v) -> { if (v != null && !v.isBlank()) props.put(k, v); });
        return props;
    }

    @Override
    public void close() {
        stopConsuming();
        if (producer != null) { try { producer.close(Duration.ofSeconds(2)); } catch (Exception ignored) { } producer = null; }
        if (admin != null) { try { admin.close(Duration.ofSeconds(2)); } catch (Exception ignored) { } admin = null; }
    }
}
