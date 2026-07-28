package com.nexuslink.plugin.codegen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers the {@link CodeGenerator}s on the classpath, mirroring
 * {@link com.nexuslink.plugin.ExtensionRegistry}'s contract: first registration of a protocol id
 * wins, later collisions are reported through {@link #duplicateProtocolIds()} rather than throwing,
 * so one bad jar can't take the code-generation feature down.
 *
 * <p>The UI asks {@link #generatorsFor(Object)} which generators apply to the request it holds, so a
 * new protocol becomes code-generatable by adding a provider jar — no UI change.
 */
public final class CodeGenRegistry {

    private static volatile CodeGenRegistry global;

    private final Map<String, CodeGenerator> byProtocol;
    private final List<String> duplicateProtocolIds;

    private CodeGenRegistry(Map<String, CodeGenerator> byProtocol, List<String> duplicateProtocolIds) {
        this.byProtocol = byProtocol;
        this.duplicateProtocolIds = duplicateProtocolIds;
    }

    /** Loads generators from the current thread's context classpath. */
    public static CodeGenRegistry load() {
        return fromProviders(ServiceLoader.load(CodeGenerator.class));
    }

    /** Loads generators visible to {@code classLoader}. */
    public static CodeGenRegistry load(ClassLoader classLoader) {
        return fromProviders(ServiceLoader.load(CodeGenerator.class, classLoader));
    }

    /** The lazily-loaded registry the application shares. */
    public static CodeGenRegistry global() {
        CodeGenRegistry local = global;
        if (local == null) {
            synchronized (CodeGenRegistry.class) {
                local = global;
                if (local == null) {
                    local = load();
                    global = local;
                }
            }
        }
        return local;
    }

    /**
     * Indexes an explicit set of providers — the testable seam behind {@link #load()}. Iterated in
     * order; a provider with a blank protocol id is ignored, and a second provider for an already
     * claimed id is skipped and recorded in {@link #duplicateProtocolIds()}.
     */
    public static CodeGenRegistry fromProviders(Iterable<CodeGenerator> providers) {
        Map<String, CodeGenerator> index = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (CodeGenerator generator : providers) {
            String id = generator.protocolId();
            if (id == null || id.isBlank()) continue;
            if (index.putIfAbsent(id, generator) != null) duplicates.add(id);
        }
        // Collections.unmodifiableMap, not Map.copyOf — the latter does not keep insertion order,
        // and the UI shows generators in registration order.
        return new CodeGenRegistry(Collections.unmodifiableMap(index), List.copyOf(duplicates));
    }

    /** Every registered generator, in registration order. */
    public List<CodeGenerator> generators() {
        return List.copyOf(byProtocol.values());
    }

    /** The generator registered for {@code protocolId}, if any. */
    public Optional<CodeGenerator> byProtocol(String protocolId) {
        return Optional.ofNullable(byProtocol.get(protocolId));
    }

    /** The generators that can render {@code request}, in registration order. */
    public List<CodeGenerator> generatorsFor(Object request) {
        if (request == null) return List.of();
        List<CodeGenerator> out = new ArrayList<>();
        for (CodeGenerator generator : byProtocol.values()) {
            if (generator.supports(request)) out.add(generator);
        }
        return List.copyOf(out);
    }

    /** The first generator that can render {@code request}. */
    public Optional<CodeGenerator> firstFor(Object request) {
        return generatorsFor(request).stream().findFirst();
    }

    /** Protocol ids claimed more than once; the first registration is the one that is used. */
    public List<String> duplicateProtocolIds() {
        return duplicateProtocolIds;
    }

    public int size() {
        return byProtocol.size();
    }
}
