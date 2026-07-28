package com.nexuslink.protocol.redis;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A bounded, thread-safe log of delivered Pub/Sub messages — the model behind the Redis Pub/Sub
 * panel. Pure: no I/O and no JavaFX.
 *
 * <p>Messages arrive on {@link RedisSubscriber}'s reader thread while the UI reads snapshots, hence
 * the synchronisation. Adding past {@link #capacity()} drops the oldest entry, so subscribing to a
 * firehose pattern can't exhaust memory.
 */
public final class RedisMessageLog {

    /** Entries kept when no explicit capacity is given. */
    public static final int DEFAULT_CAPACITY = 5_000;

    /** One delivered message, stamped on arrival. {@code pattern} is null for a plain subscription. */
    public record Entry(long epochMillis, String channel, String pattern, String payload) {

        public Entry {
            channel = channel == null ? "" : channel;
            payload = payload == null ? "" : payload;
        }

        /** Stamps {@code message} with the current time. */
        public static Entry of(RedisMessage message) {
            return new Entry(System.currentTimeMillis(),
                    message.channel(), message.pattern(), message.payload());
        }

        public Instant timestamp() {
            return Instant.ofEpochMilli(epochMillis);
        }

        /** {@code true} when the message came from a pattern subscription. */
        public boolean isPattern() {
            return pattern != null;
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int capacity;

    public RedisMessageLog() {
        this(DEFAULT_CAPACITY);
    }

    public RedisMessageLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("log capacity must be positive but was " + capacity);
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Appends a message, evicting the oldest when full.
     *
     * @return the evicted entry, or {@code null} if nothing was dropped
     */
    public synchronized Entry add(Entry entry) {
        if (entry == null) return null;
        Entry evicted = entries.size() >= capacity ? entries.pollFirst() : null;
        entries.addLast(entry);
        return evicted;
    }

    /** Appends a delivered message. */
    public Entry add(RedisMessage message) {
        return message == null ? null : add(Entry.of(message));
    }

    /** A snapshot of the retained entries, oldest first. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
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
     * The entries whose channel matches a Redis glob ({@code *}, {@code ?}, {@code [..]}) — the same
     * matcher Redis itself uses, via {@link RedisGlob}. A blank pattern matches everything.
     */
    public List<Entry> matching(String channelGlob) {
        if (channelGlob == null || channelGlob.isBlank()) return entries();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (RedisGlob.matches(channelGlob, e.channel())) out.add(e);
        }
        return List.copyOf(out);
    }

    /**
     * The entries matching {@code channelGlob} whose payload also contains {@code text}
     * (case-insensitive). Either criterion may be blank to skip it.
     */
    public List<Entry> search(String channelGlob, String text) {
        List<Entry> byChannel = matching(channelGlob);
        if (text == null || text.isBlank()) return byChannel;
        String needle = text.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : byChannel) {
            if (e.payload().toLowerCase(Locale.ROOT).contains(needle)) out.add(e);
        }
        return List.copyOf(out);
    }
}
