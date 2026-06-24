package com.nexuslink.plugin;

import java.util.List;
import java.util.Map;

/**
 * Browses the live object hierarchy of a connected resource. Implementations wrap a connected
 * protocol service (JDBC, Mongo, Kafka, MQ, …) and answer lazily so large trees stay responsive.
 *
 * <p>The UI calls {@link #roots()} once a connection is established, then {@link #children}
 * on demand as the user expands nodes. All methods may perform I/O and are invoked off the
 * UI thread.
 */
public interface ResourceExplorer {

    /** Top-level nodes (e.g. databases on a server, or the server itself). */
    List<ResourceNode> roots() throws Exception;

    /** Direct children of {@code parent} (e.g. a database's collections, a table's columns). */
    List<ResourceNode> children(ResourceNode parent) throws Exception;

    /** Property bag shown in the details panel. Defaults to the node's own details. */
    default Map<String, String> details(ResourceNode node) throws Exception {
        return node.details();
    }
}
