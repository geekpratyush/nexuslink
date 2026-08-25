package com.nexuslink.protocol.ai.llm;

import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Api;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Auth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmEndpointStoreTest {

    private static LlmEndpointConfig withSecrets(String apiKey, String clientSecret) {
        return new LlmEndpointConfig(null, "Corp Gateway", Api.ANTHROPIC, "https://gw.corp", null,
                Auth.OIDC_CLIENT_CREDENTIALS, null, apiKey, null, "https://idp.corp/token",
                "svc-client", clientSecret, "llm.invoke", null, Map.of("X-Tenant", "acme"),
                "claude-opus-4-8", 4096, 0.2, null, null, List.of(), null, 0, 0);
    }

    @Test
    void savedEndpointsSurviveAReload(@TempDir Path tmp) {
        Path file = tmp.resolve("llm.json");
        var store = new LlmEndpointStore(file);
        var saved = store.save(withSecrets("${CORP_API_KEY}", "${CORP_CLIENT_SECRET}"));

        var reloaded = new LlmEndpointStore(file).byId(saved.id()).orElseThrow();
        assertEquals("Corp Gateway", reloaded.name());
        assertEquals("https://gw.corp", reloaded.baseUrl());
        assertEquals(Auth.OIDC_CLIENT_CREDENTIALS, reloaded.auth());
        assertEquals("https://idp.corp/token", reloaded.tokenUrl());
        assertEquals("llm.invoke", reloaded.scope());
        assertEquals(4096, reloaded.maxTokens());
        assertEquals(0.2, reloaded.temperature(), 1e-9);
        assertEquals("acme", reloaded.extraHeaders().get("X-Tenant"));
    }

    @Test
    void environmentReferencesArePersistedButLiteralSecretsAreNot(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("llm.json");
        var store = new LlmEndpointStore(file);
        var saved = store.save(withSecrets("sk-literal-key", "literal-client-secret"));

        assertNull(saved.apiKey(), "a typed-in key is dropped rather than written to disk");
        assertNull(saved.clientSecret());

        String onDisk = Files.readString(file);
        assertFalse(onDisk.contains("sk-literal-key"), onDisk);
        assertFalse(onDisk.contains("literal-client-secret"), onDisk);

        var withRefs = new LlmEndpointStore(tmp.resolve("b.json"))
                .save(withSecrets("${CORP_API_KEY}", "${CORP_CLIENT_SECRET}"));
        assertEquals("${CORP_API_KEY}", withRefs.apiKey());
        assertEquals("${CORP_CLIENT_SECRET}", withRefs.clientSecret());
    }

    @Test
    void nonSecretFieldsSurviveSecretStripping() {
        var stripped = LlmEndpointStore.withoutLiteralSecrets(withSecrets("literal", "literal"), "id-1");
        assertEquals("id-1", stripped.id());
        assertEquals("svc-client", stripped.clientId(), "the client id is not a secret");
        assertEquals("https://idp.corp/token", stripped.tokenUrl());
    }

    @Test
    void savingTwiceUpdatesInPlace(@TempDir Path tmp) {
        var store = new LlmEndpointStore(tmp.resolve("llm.json"));
        var first = store.save(withSecrets("${K}", "${S}"));

        var renamed = new LlmEndpointConfig(first.id(), "Renamed", first.api(), first.baseUrl(),
                first.path(), first.auth(), first.apiKeyHeader(), first.apiKey(), first.bearerToken(),
                first.tokenUrl(), first.clientId(), first.clientSecret(), first.scope(),
                first.anthropicVersion(), first.extraHeaders(), first.model(), first.maxTokens(),
                first.temperature(), first.topP(), first.topK(), first.stopSequences(),
                first.thinkingBudgetTokens(), first.connectTimeoutMs(), first.readTimeoutMs());
        store.save(renamed);

        assertEquals(1, store.all().size());
        assertEquals("Renamed", store.byId(first.id()).orElseThrow().name());
    }

    @Test
    void deleteRemovesTheEndpoint(@TempDir Path tmp) {
        Path file = tmp.resolve("llm.json");
        var store = new LlmEndpointStore(file);
        var saved = store.save(withSecrets("${K}", "${S}"));

        assertTrue(store.delete(saved.id()));
        assertFalse(store.delete(saved.id()));
        assertTrue(new LlmEndpointStore(file).all().isEmpty());
    }

    @Test
    void aNameIsRequired(@TempDir Path tmp) {
        var store = new LlmEndpointStore(tmp.resolve("llm.json"));
        var unnamed = LlmEndpointConfig.of("  ", Api.ANTHROPIC, "https://gw.corp", "m");
        assertThrows(IllegalArgumentException.class, () -> store.save(unnamed));
    }

    @Test
    void aCorruptFileDegradesToAnEmptyList(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("llm.json");
        Files.writeString(file, "not json at all");
        assertTrue(new LlmEndpointStore(file).all().isEmpty());
    }
}
