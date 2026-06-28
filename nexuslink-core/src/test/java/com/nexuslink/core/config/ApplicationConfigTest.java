package com.nexuslink.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationConfigTest {

    @Test
    void roundTripsValuesAcrossInstances(@TempDir Path dir) {
        Path file = dir.resolve("settings.json");
        ApplicationConfig a = new ApplicationConfig(file);
        a.setString("ui.theme", "dark");
        a.setInt("net.timeout", 1234);
        a.setBoolean("telemetry", true);
        a.setDouble("ui.scale", 1.5);
        a.save();

        ApplicationConfig b = new ApplicationConfig(file);
        b.reload();
        assertEquals("dark", b.getString("ui.theme", "system"));
        assertEquals(1234, b.getInt("net.timeout", 0));
        assertTrue(b.getBoolean("telemetry", false));
        assertEquals(1.5, b.getDouble("ui.scale", 0.0), 0.0001);
    }

    @Test
    void returnsDefaultsWhenKeysAbsent(@TempDir Path dir) {
        ApplicationConfig c = new ApplicationConfig(dir.resolve("settings.json"));
        assertEquals("system", c.getString("missing", "system"));
        assertEquals(42, c.getInt("missing", 42));
        assertFalse(c.getBoolean("missing", false));
        assertEquals(3.14, c.getDouble("missing", 3.14), 0.0001);
    }

    @Test
    void missingFileYieldsDefaultsAndDoesNotThrow(@TempDir Path dir) {
        // No file on disk yet.
        ApplicationConfig c = new ApplicationConfig(dir.resolve("does-not-exist.json"));
        assertEquals("fallback", c.getString("any", "fallback"));
    }

    @Test
    void malformedFileFallsBackToDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{ this is : not valid json ]");
        ApplicationConfig c = new ApplicationConfig(file);
        assertEquals("def", c.getString("ui.theme", "def"));
        // And it should be usable afterwards (overwrites the bad file on next set).
        c.setString("ui.theme", "light");
        c.save();
        ApplicationConfig reread = new ApplicationConfig(file);
        assertEquals("light", reread.getString("ui.theme", "def"));
    }

    @Test
    void badNumberFormatFallsBackToDefault(@TempDir Path dir) {
        ApplicationConfig c = new ApplicationConfig(dir.resolve("settings.json"));
        c.setString("port", "not-a-number");
        assertEquals(8080, c.getInt("port", 8080));
        assertEquals(2.0, c.getDouble("port", 2.0), 0.0001);
    }

    @Test
    void writesHumanReadableJsonOverlay(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        ApplicationConfig c = new ApplicationConfig(file);
        c.setString("ui.theme", "dark");
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("ui.theme"));
        assertTrue(json.contains("dark"));
    }

    @Test
    void reloadDiscardsUnsavedInMemoryView(@TempDir Path dir) {
        Path file = dir.resolve("settings.json");
        ApplicationConfig c = new ApplicationConfig(file);
        c.setString("ui.theme", "dark"); // persisted eagerly
        // Simulate an external rewrite of the file by a fresh instance.
        ApplicationConfig other = new ApplicationConfig(file);
        other.setString("ui.theme", "light");
        c.reload();
        assertEquals("light", c.getString("ui.theme", "system"));
    }
}
