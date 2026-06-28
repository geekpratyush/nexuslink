package com.nexuslink.core.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * App-facing settings layer over {@link ApplicationConfig}. It exposes a small, documented set of
 * named, typed preferences with sensible defaults and a lightweight change-notification mechanism so
 * UI (or any other component) can react when a setting changes — without pulling in an event bus or
 * any external dependency.
 *
 * <p>Every setter writes through to the backing {@link ApplicationConfig} (which persists eagerly)
 * and then fires the registered listeners with the changed key's name.
 */
public final class SettingsService {

    // ---- preference keys & defaults -----------------------------------------

    /** UI theme: {@code "system"}, {@code "light"} or {@code "dark"}. */
    public static final String KEY_THEME = "ui.theme";
    public static final String DEFAULT_THEME = "system";

    /** Connection establishment timeout, in milliseconds. */
    public static final String KEY_CONNECT_TIMEOUT_MS = "net.connectTimeoutMs";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 30_000;

    /** Socket/read timeout, in milliseconds. */
    public static final String KEY_READ_TIMEOUT_MS = "net.readTimeoutMs";
    public static final int DEFAULT_READ_TIMEOUT_MS = 60_000;

    /** Last directory the user opened/saved a file from, for file-dialog convenience. */
    public static final String KEY_LAST_DIRECTORY = "ui.lastUsedDirectory";
    public static final String DEFAULT_LAST_DIRECTORY = System.getProperty("user.home");

    /** Whether anonymous usage telemetry is enabled. Off by default (opt-in). */
    public static final String KEY_TELEMETRY_ENABLED = "telemetry.enabled";
    public static final boolean DEFAULT_TELEMETRY_ENABLED = false;

    private final ApplicationConfig config;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public SettingsService() {
        this(new ApplicationConfig());
    }

    SettingsService(ApplicationConfig config) {
        this.config = config;
    }

    // ---- change notification ------------------------------------------------

    /** Registers a listener invoked with the changed key whenever a setting is updated. */
    public void addChangeListener(Consumer<String> listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeChangeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    private void fire(String key) {
        for (Consumer<String> l : listeners) l.accept(key);
    }

    // ---- typed settings -----------------------------------------------------

    public String getTheme() {
        return config.getString(KEY_THEME, DEFAULT_THEME);
    }

    public void setTheme(String theme) {
        config.setString(KEY_THEME, theme);
        fire(KEY_THEME);
    }

    public int getConnectTimeoutMs() {
        return config.getInt(KEY_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public void setConnectTimeoutMs(int millis) {
        config.setInt(KEY_CONNECT_TIMEOUT_MS, millis);
        fire(KEY_CONNECT_TIMEOUT_MS);
    }

    public int getReadTimeoutMs() {
        return config.getInt(KEY_READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public void setReadTimeoutMs(int millis) {
        config.setInt(KEY_READ_TIMEOUT_MS, millis);
        fire(KEY_READ_TIMEOUT_MS);
    }

    public String getLastUsedDirectory() {
        return config.getString(KEY_LAST_DIRECTORY, DEFAULT_LAST_DIRECTORY);
    }

    public void setLastUsedDirectory(String path) {
        config.setString(KEY_LAST_DIRECTORY, path);
        fire(KEY_LAST_DIRECTORY);
    }

    public boolean isTelemetryEnabled() {
        return config.getBoolean(KEY_TELEMETRY_ENABLED, DEFAULT_TELEMETRY_ENABLED);
    }

    public void setTelemetryEnabled(boolean enabled) {
        config.setBoolean(KEY_TELEMETRY_ENABLED, enabled);
        fire(KEY_TELEMETRY_ENABLED);
    }

    /** Exposes the backing store for advanced/ad-hoc keys not surfaced as named settings. */
    public ApplicationConfig config() {
        return config;
    }
}
