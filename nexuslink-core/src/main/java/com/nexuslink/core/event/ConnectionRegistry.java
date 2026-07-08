package com.nexuslink.core.event;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide tally of live connections by {@link ConnState}, fed by protocol services as they
 * connect / work / fail / disconnect. Each transition is also posted as a {@link ConnectionEvent}
 * on an {@link EventBus} so UI panels can refresh live.
 *
 * <p>A connection is identified by a caller-chosen {@code key} (typically {@code protocol + "@" +
 * target}); re-recording the same key just moves it between states. {@link ConnState#CLOSED}
 * removes the entry entirely.
 *
 * <p>Thread-safe. Instantiable with an injected bus for tests; {@link #global()} is the app-wide
 * singleton bound to {@link EventBus#get()}.
 */
public final class ConnectionRegistry {

    private static final ConnectionRegistry GLOBAL = new ConnectionRegistry(EventBus.get());

    /** Immutable snapshot of counts. */
    public record Counts(int active, int idle, int failed) {
        public int total() { return active + idle + failed; }
    }

    private record Entry(String protocol, String target, ConnState state) {}

    private final EventBus bus;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public ConnectionRegistry(EventBus bus) {
        this.bus = bus;
    }

    public static ConnectionRegistry global() {
        return GLOBAL;
    }

    /** Mark {@code key} ACTIVE (connected + work in flight). */
    public void active(String key, String protocol, String target) {
        transition(key, protocol, target, ConnState.ACTIVE);
    }

    /** Mark {@code key} IDLE (connected, no work in flight). */
    public void idle(String key, String protocol, String target) {
        transition(key, protocol, target, ConnState.IDLE);
    }

    /** Mark {@code key} FAILED (last operation errored). */
    public void failed(String key, String protocol, String target) {
        transition(key, protocol, target, ConnState.FAILED);
    }

    /** Drop {@code key} from the tally (disconnected). No-op if unknown. */
    public void closed(String key, String protocol, String target) {
        if (entries.remove(key) != null) {
            bus.post(new ConnectionEvent(protocol, target, ConnState.CLOSED, System.currentTimeMillis()));
        }
    }

    private void transition(String key, String protocol, String target, ConnState state) {
        Entry prev = entries.put(key, new Entry(protocol, target, state));
        if (prev != null && prev.state() == state) return; // no real change, no event
        bus.post(new ConnectionEvent(protocol, target, state, System.currentTimeMillis()));
    }

    /** Aggregate counts across all protocols. */
    public Counts counts() {
        int active = 0, idle = 0, failed = 0;
        for (Entry e : entries.values()) {
            switch (e.state()) {
                case ACTIVE -> active++;
                case IDLE -> idle++;
                case FAILED -> failed++;
                case CLOSED -> { /* never stored */ }
            }
        }
        return new Counts(active, idle, failed);
    }

    /** Counts broken down per protocol label, sorted by protocol name. */
    public Map<String, Counts> byProtocol() {
        Map<String, EnumMap<ConnState, Integer>> acc = new TreeMap<>();
        for (Entry e : entries.values()) {
            var m = acc.computeIfAbsent(e.protocol(), k -> new EnumMap<>(ConnState.class));
            m.merge(e.state(), 1, Integer::sum);
        }
        Map<String, Counts> out = new TreeMap<>();
        acc.forEach((proto, m) -> out.put(proto, new Counts(
                m.getOrDefault(ConnState.ACTIVE, 0),
                m.getOrDefault(ConnState.IDLE, 0),
                m.getOrDefault(ConnState.FAILED, 0))));
        return out;
    }

    /** Test/reset hook — clears all tracked connections without emitting events. */
    public void reset() {
        entries.clear();
    }
}
