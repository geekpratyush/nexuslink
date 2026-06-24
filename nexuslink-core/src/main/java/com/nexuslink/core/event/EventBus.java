package com.nexuslink.core.event;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Typed, async-capable pub/sub event bus.
 * Listeners are held weakly — no need to unsubscribe if the subscriber is GC'd.
 * Post on any thread; listeners fire on the specified executor (defaults to caller's thread).
 */
public final class EventBus {

    private static final EventBus INSTANCE = new EventBus();

    private final Map<Class<?>, List<WeakReference<Consumer<Object>>>> listeners =
            new ConcurrentHashMap<>();
    private final Executor asyncExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    private EventBus() {}

    public static EventBus get() {
        return INSTANCE;
    }

    /** Subscribe to events of type {@code eventType}. */
    @SuppressWarnings("unchecked")
    public <T> Subscription subscribe(Class<T> eventType, Consumer<T> listener) {
        var refs = listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        WeakReference<Consumer<Object>> ref = new WeakReference<>((Consumer<Object>) listener);
        refs.add(ref);
        return () -> refs.remove(ref);
    }

    /** Post synchronously on the calling thread. */
    public void post(Object event) {
        dispatch(event, Runnable::run);
    }

    /** Post asynchronously on a virtual thread. */
    public void postAsync(Object event) {
        dispatch(event, asyncExecutor);
    }

    private void dispatch(Object event, Executor executor) {
        var refs = listeners.get(event.getClass());
        if (refs == null) return;
        refs.removeIf(ref -> {
            Consumer<Object> consumer = ref.get();
            if (consumer == null) return true; // GC'd — clean up
            executor.execute(() -> consumer.accept(event));
            return false;
        });
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
