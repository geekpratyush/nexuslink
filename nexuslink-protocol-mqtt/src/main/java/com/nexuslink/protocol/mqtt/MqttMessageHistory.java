package com.nexuslink.protocol.mqtt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A bounded, in-memory log of {@link MqttHistoryEntry} — the model behind the MQTT view's message
 * list. Pure: no I/O and no JavaFX (see {@link MqttHistoryStore} for the on-disk half).
 *
 * <p>Entries are held oldest-first and capped at {@link #capacity()}; adding past the cap drops the
 * oldest, so a long-running subscription to a chatty topic can't grow without bound.
 * {@link #matching(String)} filters with the real MQTT wildcard rules via {@link MqttTopicFilter},
 * so {@code sensors/#} behaves in the history exactly as it does in a subscription.
 *
 * <p>Thread-safe: MQTT deliveries arrive on the Paho thread while the UI reads snapshots.
 */
public final class MqttMessageHistory {

    /** Entries kept when no explicit capacity is given. */
    public static final int DEFAULT_CAPACITY = 5_000;

    private final Deque<MqttHistoryEntry> entries = new ArrayDeque<>();
    private final int capacity;

    public MqttMessageHistory() {
        this(DEFAULT_CAPACITY);
    }

    public MqttMessageHistory(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("history capacity must be positive but was " + capacity);
        }
        this.capacity = capacity;
    }

    /** The maximum number of entries retained. */
    public int capacity() {
        return capacity;
    }

    /**
     * Appends an entry, evicting the oldest if the log is full.
     *
     * @return the entry that was evicted to make room, or {@code null} if nothing was dropped
     */
    public synchronized MqttHistoryEntry add(MqttHistoryEntry entry) {
        if (entry == null) return null;
        MqttHistoryEntry evicted = entries.size() >= capacity ? entries.pollFirst() : null;
        entries.addLast(entry);
        return evicted;
    }

    /** Appends every entry in order, keeping only the newest {@link #capacity()} of them. */
    public synchronized void addAll(Collection<MqttHistoryEntry> batch) {
        for (MqttHistoryEntry e : batch) {
            add(e);
        }
    }

    /** A snapshot of the retained entries, oldest first. */
    public synchronized List<MqttHistoryEntry> entries() {
        return List.copyOf(entries);
    }

    /** The most recent {@code n} entries, oldest first (fewer if the log is shorter). */
    public synchronized List<MqttHistoryEntry> recent(int n) {
        List<MqttHistoryEntry> all = new ArrayList<>(entries);
        return n >= all.size() ? List.copyOf(all) : List.copyOf(all.subList(all.size() - n, all.size()));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /**
     * The entries whose topic matches an MQTT topic filter ({@code +} / {@code #} wildcards).
     * A blank filter matches everything; an invalid filter matches nothing (so a half-typed
     * filter in the UI shows an empty list rather than throwing).
     */
    public List<MqttHistoryEntry> matching(String topicFilter) {
        if (topicFilter == null || topicFilter.isBlank()) return entries();
        if (!MqttTopicFilter.isValidFilter(topicFilter)) return List.of();
        MqttTopicFilter compiled = MqttTopicFilter.compile(topicFilter);
        List<MqttHistoryEntry> out = new ArrayList<>();
        for (MqttHistoryEntry e : entries()) {
            if (compiled.matches(e.topic())) out.add(e);
        }
        return List.copyOf(out);
    }

    /**
     * The entries whose topic matches {@code topicFilter} <em>and</em> whose payload contains
     * {@code text} (case-insensitive). Either criterion may be blank to skip it.
     */
    public List<MqttHistoryEntry> search(String topicFilter, String text) {
        List<MqttHistoryEntry> byTopic = matching(topicFilter);
        if (text == null || text.isBlank()) return byTopic;
        String needle = text.toLowerCase(Locale.ROOT);
        List<MqttHistoryEntry> out = new ArrayList<>();
        for (MqttHistoryEntry e : byTopic) {
            if (e.payload().toLowerCase(Locale.ROOT).contains(needle)) out.add(e);
        }
        return List.copyOf(out);
    }
}
