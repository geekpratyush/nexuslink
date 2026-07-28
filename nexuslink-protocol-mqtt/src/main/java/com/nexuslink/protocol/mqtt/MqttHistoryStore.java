package com.nexuslink.protocol.mqtt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk half of the MQTT message history: an append-only, line-per-entry file that survives
 * restarts. Each line is a {@link MqttHistoryEntry#encode()} record, so the file is greppable and a
 * damaged or hand-edited line is skipped rather than failing the whole load.
 *
 * <p>Writes are append-only (cheap per message); the file is trimmed to {@link #maxEntries()} on
 * {@link #load()}, which is the one moment the whole file is already in memory. Every operation is
 * best-effort with respect to I/O errors — history is a convenience, and a read-only home directory
 * must never break the MQTT client itself, so failures are reported through
 * {@link #lastError()} instead of propagating.
 */
public final class MqttHistoryStore {

    /** Lines retained in the file; older ones are dropped when {@link #load()} compacts it. */
    public static final int DEFAULT_MAX_ENTRIES = 5_000;

    private final Path file;
    private final int maxEntries;
    private final Object lock = new Object();
    private volatile IOException lastError;

    /** A store over {@code ~/.nexuslink/mqtt-history.log}. */
    public static MqttHistoryStore userDefault() {
        return new MqttHistoryStore(
                Path.of(System.getProperty("user.home"), ".nexuslink", "mqtt-history.log"),
                DEFAULT_MAX_ENTRIES);
    }

    public MqttHistoryStore(Path file) {
        this(file, DEFAULT_MAX_ENTRIES);
    }

    public MqttHistoryStore(Path file, int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive but was " + maxEntries);
        }
        this.file = file;
        this.maxEntries = maxEntries;
    }

    public Path file() {
        return file;
    }

    public int maxEntries() {
        return maxEntries;
    }

    /** The last I/O failure swallowed by this store, or {@code null} if every operation succeeded. */
    public IOException lastError() {
        return lastError;
    }

    /**
     * Appends one entry to the file, creating it (and {@code ~/.nexuslink}) if needed.
     *
     * @return {@code true} if the entry was written
     */
    public boolean append(MqttHistoryEntry entry) {
        if (entry == null) return false;
        synchronized (lock) {
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(file, entry.encode() + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return true;
            } catch (IOException e) {
                lastError = e;
                return false;
            }
        }
    }

    /**
     * Reads the persisted entries oldest-first, keeping at most {@link #maxEntries()} of the newest,
     * and rewrites the file when it had grown past that cap. Unreadable lines are skipped; a missing
     * file simply yields an empty list.
     */
    public List<MqttHistoryEntry> load() {
        synchronized (lock) {
            List<String> lines;
            try {
                if (!Files.exists(file)) return List.of();
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                lastError = e;
                return List.of();
            }
            List<MqttHistoryEntry> parsed = new ArrayList<>(Math.min(lines.size(), maxEntries));
            for (String line : lines) {
                MqttHistoryEntry entry = MqttHistoryEntry.decode(line);
                if (entry != null) parsed.add(entry);
            }
            boolean trimmed = parsed.size() > maxEntries;
            if (trimmed) {
                parsed = new ArrayList<>(parsed.subList(parsed.size() - maxEntries, parsed.size()));
            }
            // Rewrite when we dropped entries, or when unparsable lines mean the file no longer
            // matches what we just loaded — either way the file should reflect the kept history.
            if (trimmed || parsed.size() != lines.size()) {
                rewrite(parsed);
            }
            return List.copyOf(parsed);
        }
    }

    /** Replaces the file's contents with {@code entries} (oldest first). */
    public boolean rewrite(List<MqttHistoryEntry> entries) {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            for (MqttHistoryEntry e : entries) {
                sb.append(e.encode()).append(System.lineSeparator());
            }
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return true;
            } catch (IOException e) {
                lastError = e;
                return false;
            }
        }
    }

    /** Deletes the persisted history. */
    public boolean clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(file);
                return true;
            } catch (IOException e) {
                lastError = e;
                return false;
            }
        }
    }
}
