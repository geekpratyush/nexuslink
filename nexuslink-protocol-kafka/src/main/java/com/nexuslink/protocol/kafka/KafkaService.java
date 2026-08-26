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
    /**
     * One consumed record. {@code headers} carries the record's headers in order (Kafka allows a name
     * to repeat), and a {@code null} {@code value} means a <b>tombstone</b> — the record that deletes a
     * key on a compacted topic. Rendering that as an empty string, as the browser used to, hides the
     * single most consequential kind of record on a compacted topic.
     */
    public record KafkaMessage(int partition, long offset, long timestamp, String key, String value,
                               List<ProduceSpec.Header> headers) {

        public KafkaMessage {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }

        /** Backwards-compatible shape for callers that do not care about headers. */
        public KafkaMessage(int partition, long offset, long timestamp, String key, String value) {
            this(partition, offset, timestamp, key, value, List.of());
        }

        /** {@code true} for a null-valued record — a compaction tombstone. */
        public boolean isTombstone() { return value == null; }

        /** The headers as {@code name: value} lines, for the message detail panel. */
        public String headerText() { return ProduceSpec.renderHeaders(headers); }

        /** The headers as a map, for header-aware filtering; a repeated name keeps its last value. */
        public Map<String, String> headersAsMap() {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (ProduceSpec.Header header : headers) out.put(header.name(), header.value());
            return out;
        }
    }

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

    // ---- seek and replay (KF-7) ----

    /**
     * Browses from a starting point rather than from the beginning or end: a specific
     * partition/offset, or the first record at or after a timestamp.
     *
     * <p>Like {@link #browse}, this joins no consumer group and commits nothing — reading history to
     * find one bad record should never disturb the consumers that are running.
     *
     * @param partition the partition to read, or {@code null} for every partition
     * @param offset    the offset to start at, or {@code null} to use {@code timestamp}
     * @param timestamp epoch millis to seek to, or {@code null} to start at the beginning
     */
    public List<KafkaMessage> browseFrom(String topic, Integer partition, Long offset, Long timestamp,
                                         int maxMessages) {
        Properties props = base();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, STR_DE);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, STR_DE);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<KafkaMessage> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            List<PartitionInfo> parts = c.partitionsFor(topic, Duration.ofSeconds(10));
            if (parts == null || parts.isEmpty()) return out;

            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo p : parts) {
                if (partition == null || p.partition() == partition) {
                    tps.add(new TopicPartition(topic, p.partition()));
                }
            }
            if (tps.isEmpty()) return out;
            c.assign(tps);

            if (offset != null && partition != null) {
                c.seek(tps.get(0), offset);
            } else if (timestamp != null) {
                Map<TopicPartition, Long> query = new java.util.LinkedHashMap<>();
                for (TopicPartition tp : tps) query.put(tp, timestamp);
                var found = c.offsetsForTimes(query);
                for (TopicPartition tp : tps) {
                    var at = found.get(tp);
                    // No record at or after that time: the partition's end is the honest answer, and
                    // seeking there returns nothing rather than replaying from the start.
                    if (at == null) c.seekToEnd(List.of(tp));
                    else c.seek(tp, at.offset());
                }
            } else {
                c.seekToBeginning(tps);
            }

            int emptyPolls = 0;
            while (out.size() < maxMessages && emptyPolls < 3) {
                ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));
                if (records.isEmpty()) { emptyPolls++; continue; }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> r : records) {
                    out.add(new KafkaMessage(r.partition(), r.offset(), r.timestamp(), r.key(),
                            r.value(), headersOf(r)));
                    if (out.size() >= maxMessages) break;
                }
            }
        }
        return out;
    }

    /**
     * Re-produces messages to another topic — "replay this to the dev cluster", or back onto the
     * source topic after a fix.
     *
     * <p>Headers and keys are carried across; the partition and offset deliberately are not, because
     * the target topic may be partitioned differently and pinning a replay to partition 7 of a
     * three-partition topic would fail. {@code keepTimestamps} preserves the original timestamps,
     * which matters when the target is time-indexed and not when it is not.
     *
     * @return how many messages were re-produced
     */
    public int replay(List<KafkaMessage> messages, String targetTopic, boolean keepTimestamps)
            throws Exception {
        if (messages == null || messages.isEmpty()) return 0;
        int sent = 0;
        for (KafkaMessage message : messages) {
            ProduceSpec spec = new ProduceSpec(targetTopic, message.key(), message.value(),
                    message.isTombstone(), message.headers(), null,
                    keepTimestamps ? message.timestamp() : null);
            // A replayed tombstone with no key cannot be re-sent as a tombstone; send it as a null
            // value on the target's own terms rather than failing the whole replay.
            if (!spec.isValid() && spec.tombstone()) {
                spec = new ProduceSpec(targetTopic, message.key(), null, false, message.headers(),
                        null, keepTimestamps ? message.timestamp() : null);
            }
            send(spec);
            sent++;
        }
        return sent;
    }

    // ---- topic and broker configuration (KF-1) ----

    /** One configuration entry as the broker reports it. */
    public record ConfigEntryView(String name, String value, boolean isDefault, boolean readOnly,
                                  boolean sensitive, String source) {}

    /**
     * A topic's effective configuration, defaults included and marked as such — you cannot decide
     * whether to change {@code retention.ms} without seeing that its current value is the broker
     * default rather than something someone set deliberately.
     */
    public List<ConfigEntryView> topicConfig(String topic) throws Exception {
        return describeConfig(new org.apache.kafka.common.config.ConfigResource(
                org.apache.kafka.common.config.ConfigResource.Type.TOPIC, topic));
    }

    /** A broker's configuration, read-only here — changing broker config is a different blast radius. */
    public List<ConfigEntryView> brokerConfig(String brokerId) throws Exception {
        return describeConfig(new org.apache.kafka.common.config.ConfigResource(
                org.apache.kafka.common.config.ConfigResource.Type.BROKER, brokerId));
    }

    private List<ConfigEntryView> describeConfig(
            org.apache.kafka.common.config.ConfigResource resource) throws Exception {
        var described = admin.describeConfigs(List.of(resource)).all()
                .get(15, TimeUnit.SECONDS).get(resource);
        List<ConfigEntryView> out = new ArrayList<>();
        if (described == null) return out;
        for (var entry : described.entries()) {
            out.add(new ConfigEntryView(entry.name(), entry.value(), entry.isDefault(),
                    entry.isReadOnly(), entry.isSensitive(), String.valueOf(entry.source())));
        }
        out.sort(java.util.Comparator.comparing(ConfigEntryView::name));
        return out;
    }

    /** The topic config as a plain map, for {@link ConfigDiff#compare}. */
    public Map<String, String> topicConfigMap(String topic) throws Exception {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (ConfigEntryView entry : topicConfig(topic)) out.put(entry.name(), entry.value());
        return out;
    }

    /**
     * Applies configuration changes to a topic with {@code incrementalAlterConfigs} — the call that
     * changes only what is named, rather than {@code alterConfigs}, which replaces the whole set and
     * silently resets anything the caller forgot to send.
     *
     * @param changes entries to SET (a null value DELETEs the entry, reverting it to the default)
     */
    public void alterTopicConfig(String topic, Map<String, String> changes) throws Exception {
        if (changes == null || changes.isEmpty()) return;
        var resource = new org.apache.kafka.common.config.ConfigResource(
                org.apache.kafka.common.config.ConfigResource.Type.TOPIC, topic);
        List<org.apache.kafka.clients.admin.AlterConfigOp> ops = new ArrayList<>();
        changes.forEach((name, value) -> ops.add(new org.apache.kafka.clients.admin.AlterConfigOp(
                new org.apache.kafka.clients.admin.ConfigEntry(name, value),
                value == null ? org.apache.kafka.clients.admin.AlterConfigOp.OpType.DELETE
                        : org.apache.kafka.clients.admin.AlterConfigOp.OpType.SET)));
        admin.incrementalAlterConfigs(Map.of(resource, ops)).all().get(30, TimeUnit.SECONDS);
    }

    // ---- cluster (KF-2) ----

    /** One broker in the cluster. */
    public record BrokerView(int id, String host, int port, String rack, boolean controller) {}

    /** The brokers, with the controller flagged and the rack shown where the cluster uses racks. */
    public List<BrokerView> describeCluster() throws Exception {
        var cluster = admin.describeCluster();
        var nodes = cluster.nodes().get(15, TimeUnit.SECONDS);
        var controller = cluster.controller().get(15, TimeUnit.SECONDS);
        int controllerId = controller == null ? -1 : controller.id();
        List<BrokerView> out = new ArrayList<>();
        for (var node : nodes) {
            out.add(new BrokerView(node.id(), node.host(), node.port(), node.rack(),
                    node.id() == controllerId));
        }
        out.sort(java.util.Comparator.comparingInt(BrokerView::id));
        return out;
    }

    /** The cluster id, for the cluster panel's header. */
    public String clusterId() throws Exception {
        return admin.describeCluster().clusterId().get(15, TimeUnit.SECONDS);
    }

    // ---- consumer groups (KF-6) ----

    /** One member of a consumer group and what it is assigned. */
    public record GroupMember(String memberId, String clientId, String host, String assignment) {}

    /** The state of a group — {@code Stable}, {@code PreparingRebalance}, {@code Empty}, … */
    public String consumerGroupState(String group) throws Exception {
        var described = admin.describeConsumerGroups(List.of(group)).all()
                .get(15, TimeUnit.SECONDS).get(group);
        return described == null ? "unknown" : String.valueOf(described.state());
    }

    /** The members of a group, with their client id, host and partition assignment. */
    public List<GroupMember> consumerGroupMembers(String group) throws Exception {
        var described = admin.describeConsumerGroups(List.of(group)).all()
                .get(15, TimeUnit.SECONDS).get(group);
        List<GroupMember> out = new ArrayList<>();
        if (described == null) return out;
        for (var member : described.members()) {
            List<String> partitions = new ArrayList<>();
            member.assignment().topicPartitions()
                    .forEach(tp -> partitions.add(tp.topic() + "-" + tp.partition()));
            java.util.Collections.sort(partitions);
            out.add(new GroupMember(member.consumerId(), member.clientId(), member.host(),
                    partitions.isEmpty() ? "(none)" : String.join(", ", partitions)));
        }
        return out;
    }

    /**
     * Deletes a consumer group. The group must be empty — Kafka refuses while members are connected.
     *
     * <p>Deleting a group that is already gone is treated as success: Kafka itself removes an empty
     * group once its offsets are deleted, so "delete offsets, then delete the group" would otherwise
     * fail on its second step for having worked.
     */
    public void deleteConsumerGroup(String group) throws Exception {
        try {
            admin.deleteConsumerGroups(List.of(group)).all().get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException) return;
            throw activeGroupHint(group, e);
        }
    }

    /** Deletes a group's committed offsets for one topic, so it re-reads per its auto.offset.reset. */
    public void deleteGroupOffsets(String group, String topic) throws Exception {
        var description = describe(topic);
        java.util.Set<TopicPartition> partitions = new java.util.LinkedHashSet<>();
        for (var info : description.partitions()) partitions.add(new TopicPartition(topic, info.partition()));
        try {
            admin.deleteConsumerGroupOffsets(group, partitions).all().get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw activeGroupHint(group, e);
        }
    }

    /**
     * Turns Kafka's refusal to touch a live group into a sentence that says what to do about it.
     * "Deleting offsets of a topic is forbidden while the consumer group is actively subscribed"
     * is accurate and tells the user nothing about the fix.
     */
    private Exception activeGroupHint(String group, java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = String.valueOf(cause.getMessage());
        if (message.contains("actively subscribed") || message.contains("not empty")
                || cause instanceof org.apache.kafka.common.errors.GroupNotEmptyException
                || cause instanceof org.apache.kafka.common.errors.GroupSubscribedToTopicException) {
            String state = "unknown";
            try { state = consumerGroupState(group); } catch (Exception ignored) { }
            return new IllegalStateException("Group \"" + group + "\" is " + state
                    + " — stop its consumers first; Kafka refuses to change a group that is still running.",
                    cause);
        }
        return e;
    }

    /**
     * Waits for a group to become {@code Empty}, for the "stop the consumers, then reset" flow.
     *
     * @return {@code true} when the group emptied within the timeout
     */
    public boolean awaitGroupEmpty(String group, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if ("Empty".equals(consumerGroupState(group))) return true;
            Thread.sleep(250);
        }
        return "Empty".equals(consumerGroupState(group));
    }

    /** Sends one record (synchronously) and returns where it landed. */
    public SendResult send(String topic, String key, String value) throws Exception {
        return send(ProduceSpec.of(topic, key, value));
    }

    /**
     * Sends one fully described record: headers, an explicit partition or timestamp, and a null value
     * for a tombstone. Synchronous, returning where the record landed.
     *
     * @throws IllegalArgumentException if the spec is not sendable (see {@link ProduceSpec#validate()})
     */
    public SendResult send(ProduceSpec spec) throws Exception {
        String problem = spec.validate();
        if (!problem.isEmpty()) throw new IllegalArgumentException(problem);

        String key = spec.key() == null || spec.key().isBlank() ? null : spec.key();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                spec.topic(), spec.partition(), spec.timestamp(), key, spec.value());
        for (ProduceSpec.Header header : spec.headers()) {
            record.headers().add(header.name(), header.value() == null
                    ? null : header.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        RecordMetadata md = producer().send(record).get(15, TimeUnit.SECONDS);
        return new SendResult(md.partition(), md.offset(), md.timestamp());
    }

    /** Reads a consumed record's headers into the shape {@link KafkaMessage} carries. */
    private static List<ProduceSpec.Header> headersOf(ConsumerRecord<String, String> record) {
        List<ProduceSpec.Header> headers = new ArrayList<>();
        record.headers().forEach(h -> headers.add(new ProduceSpec.Header(h.key(),
                h.value() == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8))));
        return headers;
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
                    out.add(new KafkaMessage(r.partition(), r.offset(), r.timestamp(), r.key(),
                            r.value(), headersOf(r)));
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

        KafkaConsumer<String, String> started = new KafkaConsumer<>(props);
        started.subscribe(List.of(topic));
        consumer = started;
        consuming = true;
        // The loop owns the consumer it was given, so stopping can null the field without the loop
        // then closing null — which is what used to leave the consumer in its group until the
        // broker's session timeout expired, holding partitions long after Stop was pressed.
        Thread t = new Thread(() -> pollLoop(started, listener), "kafka-consumer");
        t.setDaemon(true);
        t.start();
    }

    private void pollLoop(KafkaConsumer<String, String> c, MessageListener listener) {
        try {
            while (consuming) {
                ConsumerRecords<String, String> records = c.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    listener.onMessage(new KafkaMessage(r.partition(), r.offset(), r.timestamp(),
                            r.key(), r.value(), headersOf(r)));
                }
            }
        } catch (WakeupException ignored) {
            // expected on stop
        } catch (Exception e) {
            if (consuming) listener.onError(e);
        } finally {
            // close() leaves the group cleanly, so a rebalance happens now rather than after the
            // session timeout.
            try { c.close(Duration.ofSeconds(5)); } catch (Exception ignored) { }
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
