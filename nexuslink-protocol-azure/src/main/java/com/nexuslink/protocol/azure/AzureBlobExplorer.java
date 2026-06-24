package com.nexuslink.protocol.azure;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link AzureBlobService} as a browsable tree: <pre>containers → blobs</pre>
 * Blob nodes carry size / last-modified / content-type / access-tier in the details panel.
 */
public final class AzureBlobExplorer implements ResourceExplorer {

    private static final int MAX_BLOBS = 1000;

    private final AzureBlobService service;

    public AzureBlobExplorer(AzureBlobService service) { this.service = service; }

    @Override
    public List<ResourceNode> roots() {
        List<ResourceNode> nodes = new ArrayList<>();
        for (String name : service.listContainers()) {
            nodes.add(ResourceNode.branch("container:" + name, name, ResourceNode.Kind.BUCKET));
        }
        return nodes;
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) {
        if (parent.kind() != ResourceNode.Kind.BUCKET) return List.of();
        String container = parent.id().substring("container:".length());
        List<ResourceNode> nodes = new ArrayList<>();
        for (AzureBlobService.BlobInfo b : service.listBlobs(container, MAX_BLOBS)) {
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Name", b.name());
            details.put("Size", humanBytes(b.size()));
            details.put("Last modified", b.lastModified());
            details.put("Content type", b.contentType() == null ? "" : b.contentType());
            details.put("Access tier", b.tier());
            nodes.add(new ResourceNode("blob:" + container + "/" + b.name(),
                    b.name(), ResourceNode.Kind.OBJECT, false, details));
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
