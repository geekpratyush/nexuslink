package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists REST collections in {@code ~/.nexuslink/rest-collections.json}.
 *
 * <p>I/O failures surface through {@link #lastError()} rather than being thrown: a read-only home
 * directory must leave the REST client usable, just without saved collections.
 */
public final class RestCollectionStore {

    /** On-disk shape — a wrapper object so the format can gain fields without breaking readers. */
    public static final class Data {
        public List<CollectionNode> collections = new ArrayList<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private final Data data;
    private String lastError;

    public RestCollectionStore() {
        this(Path.of(System.getProperty("user.home"), ".nexuslink", "rest-collections.json"));
    }

    public RestCollectionStore(Path file) {
        this.file = file;
        this.data = read();
    }

    /** The live forest — mutate it through {@link RestCollectionTree}, then {@link #save()}. */
    public List<CollectionNode> collections() {
        return data.collections;
    }

    public String lastError() {
        return lastError;
    }

    public synchronized void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            MAPPER.writeValue(file.toFile(), data);
            lastError = null;
        } catch (IOException e) {
            lastError = "Could not save collections: " + e.getMessage();
        }
    }

    private Data read() {
        if (!Files.isReadable(file)) return new Data();
        try {
            Data d = MAPPER.readValue(file.toFile(), Data.class);
            if (d.collections == null) d.collections = new ArrayList<>();
            return d;
        } catch (IOException e) {
            // A corrupt file must not wedge the client; start empty and keep the original on disk
            // (the next save overwrites it, which is the user's own explicit action).
            lastError = "Could not read collections: " + e.getMessage();
            return new Data();
        }
    }

    /** Exports the whole forest as JSON text, for sharing a collection file. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (IOException e) {
            lastError = e.getMessage();
            return "{}";
        }
    }
}
