package com.nexuslink.protocol.kafka;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exposes a connected {@link KafkaService} as a browsable tree: <pre>topics → partitions</pre>
 * Topic nodes show partition/replication counts; partition nodes show leader / replicas / ISR.
 */
public final class KafkaExplorer implements ResourceExplorer {

    private final KafkaService service;

    public KafkaExplorer(KafkaService service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() throws Exception {
        List<String> topics = service.listTopics();
        Map<String, TopicDescription> described = topics.isEmpty() ? Map.of() : service.describeAll(topics);
        List<ResourceNode> nodes = new ArrayList<>();
        for (String t : topics) {
            TopicDescription d = described.get(t);
            Map<String, String> details = new LinkedHashMap<>();
            if (d != null) {
                details.put("Partitions", String.valueOf(d.partitions().size()));
                int rf = d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size();
                details.put("Replication factor", String.valueOf(rf));
                details.put("Internal", String.valueOf(d.isInternal()));
            }
            nodes.add(new ResourceNode("topic:" + t, t, ResourceNode.Kind.TOPIC, true, details));
        }
        return nodes;
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) throws Exception {
        if (parent.kind() != ResourceNode.Kind.TOPIC) return List.of();
        String topic = parent.id().substring("topic:".length());
        TopicDescription d = service.describe(topic);
        List<ResourceNode> nodes = new ArrayList<>();
        if (d == null) return nodes;
        for (TopicPartitionInfo p : d.partitions()) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Partition", String.valueOf(p.partition()));
            details.put("Leader", p.leader() == null ? "none" : "broker " + p.leader().id());
            details.put("Replicas", p.replicas().stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(", ")));
            details.put("In-sync replicas", p.isr().stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(", ")));
            nodes.add(new ResourceNode("part:" + topic + ":" + p.partition(),
                    "partition " + p.partition(), ResourceNode.Kind.QUEUE, false, details));
        }
        return nodes;
    }
}
