package com.nexuslink.protocol.db;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Persists the drivers a user added from their own filesystem, in
 * {@code ~/.nexuslink/user-drivers.json}. Small, eagerly written, and independent of the built-in
 * catalog in {@link JdbcDriverRegistry} so an app upgrade never discards what the user installed.
 */
public final class UserDriverStore {

    /** On-disk shape — a wrapper object so the format can gain fields without breaking readers. */
    public static final class Data {
        public List<UserDriver> drivers = new ArrayList<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private Data data;

    public UserDriverStore() {
        this(Path.of(System.getProperty("user.home"), ".nexuslink", "user-drivers.json"));
    }

    public UserDriverStore(Path file) {
        this.file = file;
        this.data = read();
    }

    public synchronized List<UserDriver> all() {
        return new ArrayList<>(data.drivers);
    }

    public synchronized Optional<UserDriver> byId(String id) {
        return data.drivers.stream().filter(d -> d.id.equals(id)).findFirst();
    }

    /**
     * Adds a driver, or replaces the existing one with the same id. Returns the stored entry.
     *
     * @throws IllegalArgumentException if the jar is missing, or the name/class is blank
     */
    public synchronized UserDriver add(UserDriver driver) {
        if (driver.displayName == null || driver.displayName.isBlank()) {
            throw new IllegalArgumentException("Driver name is required");
        }
        if (driver.driverClass == null || driver.driverClass.isBlank()) {
            throw new IllegalArgumentException("Driver class is required");
        }
        if (driver.jarPath == null || !Files.isReadable(Path.of(driver.jarPath))) {
            throw new IllegalArgumentException("Driver jar not found or not readable: " + driver.jarPath);
        }
        if (driver.id == null || driver.id.isBlank()) driver.id = idFor(driver.displayName);
        data.drivers.removeIf(d -> d.id.equals(driver.id));
        data.drivers.add(driver);
        write();
        return driver;
    }

    /** Removes a user driver. Returns true if one was removed. The jar itself is left alone. */
    public synchronized boolean remove(String id) {
        boolean removed = data.drivers.removeIf(d -> d.id.equals(id));
        if (removed) write();
        return removed;
    }

    /**
     * Derives a unique id from a display name, e.g. {@code "Sybase ASE" → "user:sybase-ase"}.
     * The {@code user:} prefix guarantees no collision with a built-in catalog id.
     */
    synchronized String idFor(String displayName) {
        String slug = displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) slug = "driver";
        String candidate = "user:" + slug;
        for (int n = 2; byId(candidate).isPresent(); n++) {
            candidate = "user:" + slug + "-" + n;
        }
        return candidate;
    }

    // ---- persistence --------------------------------------------------------

    private Data read() {
        if (!Files.isReadable(file)) return new Data();
        try {
            Data read = MAPPER.readValue(Files.readString(file), Data.class);
            return read != null ? read : new Data();
        } catch (IOException e) {
            // A corrupt file must not stop the app from starting; the user can re-add their drivers.
            return new Data();
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(data));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save user drivers to " + file, e);
        }
    }
}
