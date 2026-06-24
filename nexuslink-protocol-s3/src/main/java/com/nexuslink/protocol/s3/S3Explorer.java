package com.nexuslink.protocol.s3;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link S3Service} as a browsable tree: <pre>buckets → objects</pre>
 * Object nodes carry size / last-modified / storage-class / etag in the details panel.
 */
public final class S3Explorer implements ResourceExplorer {

    private static final int MAX_OBJECTS = 1000;

    private final S3Service service;

    public S3Explorer(S3Service service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() {
        List<ResourceNode> buckets = new ArrayList<>();
        for (String name : service.listBuckets()) {
            buckets.add(ResourceNode.branch("bucket:" + name, name, ResourceNode.Kind.BUCKET));
        }
        return buckets;
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) {
        if (parent.kind() != ResourceNode.Kind.BUCKET) return List.of();
        String bucket = parent.id().substring("bucket:".length());
        List<ResourceNode> objects = new ArrayList<>();
        for (S3Service.S3Item item : service.listObjects(bucket, "", MAX_OBJECTS)) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Key", item.key());
            details.put("Size", humanBytes(item.size()));
            details.put("Last modified", item.lastModified());
            details.put("Storage class", item.storageClass() == null ? "STANDARD" : item.storageClass());
            details.put("ETag", item.etag() == null ? "" : item.etag().replace("\"", ""));
            objects.add(new ResourceNode("obj:" + bucket + "/" + item.key(),
                    item.key(), ResourceNode.Kind.OBJECT, false, details));
        }
        return objects;
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
