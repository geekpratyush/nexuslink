package com.nexuslink.protocol.ftp;

import com.nexuslink.plugin.ResourceExplorer;
import com.nexuslink.plugin.ResourceNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes a connected {@link FtpService} as a lazily-loaded directory tree (folders → files),
 * mirroring the SFTP explorer.
 */
public final class FtpExplorer implements ResourceExplorer {

    private final FtpService service;
    private final String rootPath;

    public FtpExplorer(FtpService service, String rootPath) {
        this.service = service;
        this.rootPath = rootPath == null || rootPath.isBlank() ? "/" : rootPath;
    }

    @Override
    public List<ResourceNode> roots() throws Exception {
        return toNodes(service.list(rootPath));
    }

    @Override
    public List<ResourceNode> children(ResourceNode parent) throws Exception {
        if (parent.kind() != ResourceNode.Kind.FOLDER) return List.of();
        return toNodes(service.list(parent.id().substring("dir:".length())));
    }

    private List<ResourceNode> toNodes(List<FtpService.FtpEntry> entries) {
        List<ResourceNode> nodes = new ArrayList<>();
        for (FtpService.FtpEntry e : entries) {
            if (e.directory()) {
                nodes.add(ResourceNode.branch("dir:" + e.path(), e.name(), ResourceNode.Kind.FOLDER));
            } else {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("Name", e.name());
                details.put("Path", e.path());
                details.put("Size", humanBytes(e.size()));
                details.put("Modified", e.modified());
                nodes.add(new ResourceNode("file:" + e.path(), e.name(), ResourceNode.Kind.OBJECT, false, details));
            }
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
