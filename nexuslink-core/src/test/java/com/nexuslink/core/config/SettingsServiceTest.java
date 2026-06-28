package com.nexuslink.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest {

    private static SettingsService service(Path dir) {
        return new SettingsService(new ApplicationConfig(dir.resolve("settings.json")));
    }

    @Test
    void exposesDocumentedDefaults(@TempDir Path dir) {
        SettingsService s = service(dir);
        assertEquals(SettingsService.DEFAULT_THEME, s.getTheme());
        assertEquals(SettingsService.DEFAULT_CONNECT_TIMEOUT_MS, s.getConnectTimeoutMs());
        assertEquals(SettingsService.DEFAULT_READ_TIMEOUT_MS, s.getReadTimeoutMs());
        assertEquals(SettingsService.DEFAULT_LAST_DIRECTORY, s.getLastUsedDirectory());
        assertFalse(s.isTelemetryEnabled());
    }

    @Test
    void persistsTypedSettings(@TempDir Path dir) {
        SettingsService s = service(dir);
        s.setTheme("dark");
        s.setConnectTimeoutMs(5000);
        s.setReadTimeoutMs(7000);
        s.setLastUsedDirectory("/tmp/work");
        s.setTelemetryEnabled(true);

        SettingsService reopened = service(dir);
        assertEquals("dark", reopened.getTheme());
        assertEquals(5000, reopened.getConnectTimeoutMs());
        assertEquals(7000, reopened.getReadTimeoutMs());
        assertEquals("/tmp/work", reopened.getLastUsedDirectory());
        assertTrue(reopened.isTelemetryEnabled());
    }

    @Test
    void listenerFiresWithChangedKey(@TempDir Path dir) {
        SettingsService s = service(dir);
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger();
        s.addChangeListener(key -> {
            seen.set(key);
            count.incrementAndGet();
        });

        s.setTheme("light");
        assertEquals(SettingsService.KEY_THEME, seen.get());
        assertEquals(1, count.get());

        s.setTelemetryEnabled(true);
        assertEquals(SettingsService.KEY_TELEMETRY_ENABLED, seen.get());
        assertEquals(2, count.get());
    }

    @Test
    void removedListenerNoLongerFires(@TempDir Path dir) {
        SettingsService s = service(dir);
        AtomicInteger count = new AtomicInteger();
        Consumer<String> l = key -> count.incrementAndGet();
        s.addChangeListener(l);
        s.setTheme("dark");
        s.removeChangeListener(l);
        s.setTheme("light");
        assertEquals(1, count.get());
    }

    @Test
    void backingConfigIsAccessibleForAdHocKeys(@TempDir Path dir) {
        SettingsService s = service(dir);
        s.config().setString("custom.key", "value");
        assertEquals("value", s.config().getString("custom.key", "none"));
    }
}
