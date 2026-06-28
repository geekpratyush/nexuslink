package com.nexuslink.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Low-level key/value settings store for the application.
 *
 * <p>Values are held in {@link java.util.prefs.Preferences} for fast, native key access and are
 * additionally mirrored to a human-readable JSON overlay at {@code ~/.nexuslink/settings.json}, so
 * the configuration is portable, inspectable, and easy to back up. The JSON file is loaded on
 * construction and rewritten on every change; Preferences acts as the in-process fast path.
 *
 * <p>All values are stored as strings; the typed accessors parse on read and tolerate bad input by
 * returning the supplied default. A missing or malformed JSON file simply yields the defaults — no
 * exception is ever propagated to the caller for read paths, mirroring the rest of the core stores.
 */
public final class ApplicationConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final TypeReference<LinkedHashMap<String, String>> MAP_TYPE =
            new TypeReference<>() {};

    private final Path file;
    private final Preferences prefs;
    private final Map<String, String> values = new LinkedHashMap<>();

    public ApplicationConfig() {
        this(Path.of(System.getProperty("user.home"), ".nexuslink", "settings.json"));
    }

    /**
     * Storage-path-injectable constructor (tests point this at a {@code @TempDir}). The Preferences
     * node is derived from the absolute file path so distinct stores never share native state.
     */
    ApplicationConfig(Path file) {
        this.file = file;
        this.prefs = Preferences.userRoot().node(
                "com/nexuslink/core/config/" + Integer.toHexString(file.toAbsolutePath().hashCode()));
        reload();
    }

    // ---- typed accessors ----------------------------------------------------

    public synchronized String getString(String key, String def) {
        String v = lookup(key);
        return v != null ? v : def;
    }

    public synchronized void setString(String key, String value) {
        put(key, value);
    }

    public synchronized int getInt(String key, int def) {
        String v = lookup(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public synchronized void setInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    public synchronized boolean getBoolean(String key, boolean def) {
        String v = lookup(key);
        return v != null ? Boolean.parseBoolean(v.trim()) : def;
    }

    public synchronized void setBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    public synchronized double getDouble(String key, double def) {
        String v = lookup(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public synchronized void setDouble(String key, double value) {
        put(key, Double.toString(value));
    }

    // ---- lifecycle ----------------------------------------------------------

    /** Persists the current values to the JSON overlay. */
    public synchronized void save() {
        write();
    }

    /**
     * Re-reads the JSON overlay from disk, replacing the in-memory state and re-priming the
     * Preferences mirror. A missing or malformed file leaves the store empty (defaults only).
     */
    public synchronized void reload() {
        values.clear();
        values.putAll(read());
        try {
            prefs.clear();
        } catch (BackingStoreException ignored) {
            // native store unavailable — the in-memory map still serves reads correctly
        }
        values.forEach(prefs::put);
    }

    // ---- internals ----------------------------------------------------------

    private String lookup(String key) {
        if (values.containsKey(key)) return values.get(key);
        return prefs.get(key, null);
    }

    private void put(String key, String value) {
        values.put(key, value);
        prefs.put(key, value);
        write();
    }

    private Map<String, String> read() {
        if (file == null || !Files.exists(file)) return new LinkedHashMap<>();
        try {
            Map<String, String> m = MAPPER.readValue(Files.readAllBytes(file), MAP_TYPE);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (IOException e) {
            return new LinkedHashMap<>(); // corrupt/old file — fall back to defaults, never throw
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, MAPPER.writeValueAsBytes(values));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save settings to " + file, e);
        }
    }
}
