package com.nexuslink.core.di;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Minimal DI container — avoids module system conflicts from Spring/Guice.
 * Supports singleton and prototype registrations.
 * Services register themselves; consumers call {@code AppContext.get().resolve(Type.class)}.
 */
public final class AppContext {

    private static final AppContext INSTANCE = new AppContext();

    private final Map<Class<?>, Supplier<?>> singletonSuppliers = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> singletonInstances = new ConcurrentHashMap<>();
    private final Map<Class<?>, Supplier<?>> prototypeSuppliers = new ConcurrentHashMap<>();

    private AppContext() {}

    public static AppContext get() {
        return INSTANCE;
    }

    /** Register a singleton — the supplier is called lazily on first resolve. */
    public <T> void registerSingleton(Class<T> type, Supplier<T> supplier) {
        singletonSuppliers.put(type, supplier);
    }

    /** Register an already-constructed singleton instance. */
    public <T> void registerInstance(Class<T> type, T instance) {
        singletonInstances.put(type, Objects.requireNonNull(instance));
    }

    /** Register a prototype — a new instance per resolve call. */
    public <T> void registerPrototype(Class<T> type, Supplier<T> supplier) {
        prototypeSuppliers.put(type, supplier);
    }

    @SuppressWarnings("unchecked")
    public <T> T resolve(Class<T> type) {
        // Check pre-constructed instances first
        Object instance = singletonInstances.get(type);
        if (instance != null) return (T) instance;

        // Lazy singleton
        Supplier<?> supplier = singletonSuppliers.get(type);
        if (supplier != null) {
            return (T) singletonInstances.computeIfAbsent(type, k -> supplier.get());
        }

        // Prototype
        Supplier<?> protoSupplier = prototypeSuppliers.get(type);
        if (protoSupplier != null) return (T) protoSupplier.get();

        throw new IllegalStateException("No registration for type: " + type.getName());
    }

    public boolean isRegistered(Class<?> type) {
        return singletonInstances.containsKey(type)
                || singletonSuppliers.containsKey(type)
                || prototypeSuppliers.containsKey(type);
    }

    /** For testing — reset all registrations. */
    public void reset() {
        singletonSuppliers.clear();
        singletonInstances.clear();
        prototypeSuppliers.clear();
    }
}
