package com.nexuslink.protocol.gcs;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link GcsService} as a browsable tree: <pre>buckets → objects</pre>
 * reusing the shared BUCKET/OBJECT explorer pattern (size / updated / content-type / storage-class).
 */
public final class GcsExplorer implements ResourceExplorer {

    private static final int MAX_OBJECTS = 1000;

    private final GcsService service;

    public GcsExplorer(GcsService service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() {
        List<ResourceNode> nodes = new ArrayList<>();
        for (String name : service.listBuckets()) {
            nodes.add(ResourceNode.branch("bucket:" + name, name, ResourceNode.Kind.BUCKET));
        }
        return nodes;
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) {
        if (parent.kind() != ResourceNode.Kind.BUCKET) return List.of();
        String bucket = parent.id().substring("bucket:".length());
        List<ResourceNode> nodes = new ArrayList<>();
        for (GcsService.GcsObject o : service.listObjects(bucket, MAX_OBJECTS)) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Name", o.name());
            details.put("Size", humanBytes(o.size()));
            details.put("Updated", o.updated());
            details.put("Content type", o.contentType());
            details.put("Storage class", o.storageClass());
            nodes.add(new ResourceNode("obj:" + bucket + "/" + o.name(),
                    o.name(), ResourceNode.Kind.OBJECT, false, details));
        }
        return nodes;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        do { v /= 1024.0; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format("%.1f %s", v, units[i]);
    }
}
