package com.nexuslink.core.connection;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the user's saved connections (and which bundled samples they've hidden) to
 * {@code ~/.nexuslink/connections.json}, so connections can be cached and reopened across runs.
 * Secrets themselves belong in the credential vault; profiles store references, not plaintext.
 */
public final class ConnectionStore {

    /** On-disk shape: saved profiles + the ids of hidden public samples. */
    public static final class Data {
        public List<ConnectionProfile> saved = new ArrayList<>();
        public Set<String> hiddenSamples = new LinkedHashSet<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private Data data;

    public ConnectionStore() {
        this(Path.of(System.getProperty("user.home"), ".nexuslink", "connections.json"));
    }

    public ConnectionStore(Path file) {
        this.file = file;
        this.data = read();
    }

    public synchronized List<ConnectionProfile> saved() {
        return new ArrayList<>(data.saved);
    }

    /** Inserts or updates a saved profile (matched by id). Samples are never persisted here. */
    public synchronized void save(ConnectionProfile profile) {
        profile.sample = false;
        data.saved.removeIf(p -> p.id.equals(profile.id));
        data.saved.add(profile);
        write();
    }

    public synchronized void delete(String id) {
        data.saved.removeIf(p -> p.id.equals(id));
        write();
    }

    /** Hides a bundled sample so it won't show again (corporate users can clear the samples). */
    public synchronized void hideSample(String sampleId) {
        data.hiddenSamples.add(sampleId);
        write();
    }

    public synchronized boolean isSampleHidden(String sampleId) {
        return data.hiddenSamples.contains(sampleId);
    }

    /** Restores all hidden samples. */
    public synchronized void resetSamples() {
        data.hiddenSamples.clear();
        write();
    }

    private Data read() {
        if (!Files.exists(file)) return new Data();
        try {
            return MAPPER.readValue(Files.readAllBytes(file), Data.class);
        } catch (IOException e) {
            return new Data(); // corrupt/old file — start fresh rather than fail the app
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, MAPPER.writeValueAsBytes(data));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save connections to " + file, e);
        }
    }
}
