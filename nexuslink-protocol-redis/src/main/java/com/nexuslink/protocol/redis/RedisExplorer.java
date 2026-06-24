package com.nexuslink.protocol.redis;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link RedisService} as a flat list of keys; selecting a key lazily loads its
 * type, TTL and (typed) value into the details panel.
 */
public final class RedisExplorer implements ResourceExplorer {

    private static final int MAX_KEYS = 1000;

    private final RedisService service;

    public RedisExplorer(RedisService service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() {
        List<ResourceNode> nodes = new ArrayList<>();
        for (String key : service.scanKeys("*", MAX_KEYS)) {
            nodes.add(ResourceNode.leaf("key:" + key, key, ResourceNode.Kind.FIELD));
        }
        return nodes;
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) { return List.of(); }

    @Override
    public Map<String, String> details(ResourceNode node) {
        String key = node.id().substring("key:".length());
        Map<String, String> d = new LinkedHashMap<>();
        d.put("Key", key);
        d.put("Type", service.type(key));
        long ttl = service.ttl(key);
        d.put("TTL", ttl < 0 ? "no expiry" : ttl + " s");
        d.put("Value", service.value(key));
        return d;
    }
}
