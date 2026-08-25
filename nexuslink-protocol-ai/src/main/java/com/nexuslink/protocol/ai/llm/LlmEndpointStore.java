package com.nexuslink.protocol.ai.llm;

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
import java.util.UUID;

/**
 * Persists configured LLM endpoints in {@code ~/.nexuslink/llm-endpoints.json}.
 *
 * <h2>Secrets are not written here</h2>
 * On save, any literal API key, bearer token or client secret is stripped; only
 * {@code ${ENV_VAR}} references survive to disk. A user who types a secret directly keeps it for
 * the session, and is expected to move it into an environment variable (or their own secret
 * manager) for it to persist. Writing a plaintext credential into a JSON file in the home
 * directory would be a worse default than making the user re-enter it.
 */
public final class LlmEndpointStore {

    /** On-disk shape — an object wrapper so the format can gain fields later. */
    public static final class Data {
        public List<LlmEndpointConfig> endpoints = new ArrayList<>();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private Data data;

    public LlmEndpointStore() {
        this(Path.of(System.getProperty("user.home"), ".nexuslink", "llm-endpoints.json"));
    }

    public LlmEndpointStore(Path file) {
        this.file = file;
        this.data = read();
    }

    public synchronized List<LlmEndpointConfig> all() {
        return new ArrayList<>(data.endpoints);
    }

    public synchronized Optional<LlmEndpointConfig> byId(String id) {
        return data.endpoints.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * Inserts or updates an endpoint (matched by id, assigned when absent) and returns the stored
     * form — which is the given config with any literal secrets removed.
     */
    public synchronized LlmEndpointConfig save(LlmEndpointConfig config) {
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("Endpoint name is required");
        }
        String id = config.id() == null || config.id().isBlank()
                ? slug(config.name()) + "-" + UUID.randomUUID().toString().substring(0, 8)
                : config.id();

        LlmEndpointConfig stored = withoutLiteralSecrets(config, id);
        data.endpoints.removeIf(e -> e.id().equals(id));
        data.endpoints.add(stored);
        write();
        return stored;
    }

    public synchronized boolean delete(String id) {
        boolean removed = data.endpoints.removeIf(e -> e.id().equals(id));
        if (removed) write();
        return removed;
    }

    /**
     * Returns {@code config} with any secret that is a literal value blanked out, keeping
     * {@code ${ENV_VAR}} references intact. Public so a caller can see exactly what would be
     * persisted before persisting it.
     */
    public static LlmEndpointConfig withoutLiteralSecrets(LlmEndpointConfig config, String id) {
        return new LlmEndpointConfig(
                id, config.name(), config.api(), config.baseUrl(), config.path(), config.auth(),
                config.apiKeyHeader(),
                keepOnlyEnvReference(config.apiKey()),
                keepOnlyEnvReference(config.bearerToken()),
                config.tokenUrl(), config.clientId(),
                keepOnlyEnvReference(config.clientSecret()),
                config.scope(), config.anthropicVersion(), config.extraHeaders(),
                config.model(), config.maxTokens(), config.temperature(), config.topP(),
                config.topK(), config.stopSequences(), config.thinkingBudgetTokens(),
                config.connectTimeoutMs(), config.readTimeoutMs());
    }

    private static String keepOnlyEnvReference(String secret) {
        return LlmEndpointConfig.isEnvReference(secret) ? secret.trim() : null;
    }

    private static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "endpoint" : s;
    }

    // ---- persistence --------------------------------------------------------

    private Data read() {
        if (!Files.isReadable(file)) return new Data();
        try {
            Data read = MAPPER.readValue(Files.readString(file), Data.class);
            return read != null ? read : new Data();
        } catch (IOException e) {
            return new Data(); // a corrupt file must not stop the panel from opening
        }
    }

    private void write() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(data));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save LLM endpoints to " + file, e);
        }
    }
}
