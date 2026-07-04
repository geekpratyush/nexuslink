package com.nexuslink.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link ProtocolConnector} implementations on the classpath via the JDK {@link ServiceLoader}
 * (each provider declared in {@code META-INF/services/com.nexuslink.plugin.ProtocolConnector}) and
 * indexes them by {@link ProtocolConnector#protocolId()}. Built once at startup; the UI queries it to
 * populate the connection catalogue.
 *
 * <p>Discovery is separated from indexing through {@link #fromProviders}, so the registry can be
 * unit-tested with hand-built providers without needing a real service file. Providers with a
 * null/blank id are skipped, and the first provider to claim a given id wins (a later duplicate is
 * recorded via {@link #duplicateIds()} but does not replace the winner).</p>
 */
public final class ExtensionRegistry {

    private final Map<String, ProtocolConnector> byId;
    private final List<String> duplicateIds;

    private ExtensionRegistry(Map<String, ProtocolConnector> byId, List<String> duplicateIds) {
        this.byId = byId;
        this.duplicateIds = duplicateIds;
    }

    /** Loads connectors from the current thread's context classpath. */
    public static ExtensionRegistry load() {
        return fromProviders(ServiceLoader.load(ProtocolConnector.class));
    }

    /** Loads connectors visible to {@code classLoader}. */
    public static ExtensionRegistry load(ClassLoader classLoader) {
        return fromProviders(ServiceLoader.load(ProtocolConnector.class, classLoader));
    }

    /**
     * Indexes an explicit set of providers (the testable seam behind {@link #load}). Iterated in order;
     * the first non-blank id wins, later collisions are collected in {@link #duplicateIds()}.
     */
    public static ExtensionRegistry fromProviders(Iterable<ProtocolConnector> providers) {
        Map<String, ProtocolConnector> index = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (ProtocolConnector c : providers) {
            if (c == null) continue;
            String id = c.protocolId();
            if (id == null || id.isBlank()) continue;
            if (index.putIfAbsent(id, c) != null) duplicates.add(id);
        }
        return new ExtensionRegistry(index, duplicates);
    }

    /** The connector registered for {@code protocolId}, if any. */
    public Optional<ProtocolConnector> find(String protocolId) {
        return Optional.ofNullable(byId.get(protocolId));
    }

    /** True if a connector is registered for {@code protocolId}. */
    public boolean contains(String protocolId) {
        return byId.containsKey(protocolId);
    }

    /** All registered connectors, in discovery order. */
    public List<ProtocolConnector> all() {
        return List.copyOf(byId.values());
    }

    /** The descriptors of all registered connectors, in discovery order. */
    public List<PluginDescriptor> descriptors() {
        return byId.values().stream().map(ProtocolConnector::descriptor).toList();
    }

    /** The registered protocol ids, in discovery order. */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    /** Ids that more than one provider claimed (the first-seen provider is the one kept). */
    public List<String> duplicateIds() {
        return Collections.unmodifiableList(duplicateIds);
    }

    public int size() {
        return byId.size();
    }
}
