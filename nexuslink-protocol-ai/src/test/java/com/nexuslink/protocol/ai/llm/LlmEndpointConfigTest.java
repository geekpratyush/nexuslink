package com.nexuslink.protocol.ai.llm;

import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Api;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Auth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmEndpointConfigTest {

    private static LlmEndpointConfig config(Api api, String baseUrl, Auth auth) {
        return new LlmEndpointConfig(null, "internal", api, baseUrl, null, auth, null,
                "key", "token", "https://idp/token", "client", "secret", "scope", null,
                Map.of(), "some-model", 0, null, null, null, null, null, 0, 0);
    }

    @Test
    void defaultsFillInPathHeaderAndTimeouts() {
        var c = config(Api.ANTHROPIC, "https://gw.corp/ai/", Auth.NONE);
        assertEquals("https://gw.corp/ai", c.baseUrl(), "trailing slash removed");
        assertEquals("/v1/messages", c.path());
        assertEquals("x-api-key", c.apiKeyHeader());
        assertEquals(LlmEndpointConfig.DEFAULT_ANTHROPIC_VERSION, c.anthropicVersion());
        assertEquals(LlmEndpointConfig.DEFAULT_MAX_TOKENS, c.maxTokens());
        assertTrue(c.connectTimeoutMs() > 0 && c.readTimeoutMs() > 0);
    }

    @Test
    void openAiEndpointsGetTheirOwnPathAndKeyHeader() {
        var c = config(Api.OPENAI, "https://gw.corp", Auth.NONE);
        assertEquals("/v1/chat/completions", c.path());
        assertEquals("api-key", c.apiKeyHeader());
        assertEquals("https://gw.corp/v1/chat/completions", c.requestUrl());
    }

    @Test
    void requestUrlJoinsBaseAndPathExactlyOnce() {
        var c = LlmEndpointConfig.of("x", Api.ANTHROPIC, "https://gw.corp/llm/", "m");
        assertEquals("https://gw.corp/llm/v1/messages", c.requestUrl());
    }

    @Test
    void envReferencesResolveAgainstTheEnvironmentAndLiteralsPassThrough() {
        assertEquals("plain-secret", LlmEndpointConfig.resolve("plain-secret"));
        assertEquals("", LlmEndpointConfig.resolve("${NEXUSLINK_DEFINITELY_UNSET_VAR}"),
                "an unset variable must not leak the literal ${...} as a credential");

        String path = System.getenv("PATH");
        if (path != null) assertEquals(path, LlmEndpointConfig.resolve("${PATH}"));
    }

    @Test
    void envReferencesAreRecognisable() {
        assertTrue(LlmEndpointConfig.isEnvReference("${MY_TOKEN}"));
        assertFalse(LlmEndpointConfig.isEnvReference("sk-ant-literal"));
        assertFalse(LlmEndpointConfig.isEnvReference(null));
    }

    @Test
    void validationReportsAMissingBaseUrlAndModel() {
        var c = new LlmEndpointConfig(null, "x", Api.ANTHROPIC, "", null, Auth.NONE, null, null,
                null, null, null, null, null, null, Map.of(), "", 0, null, null, null,
                List.of(), null, 0, 0);
        assertTrue(c.validationErrors().contains("Base URL is required"));
        assertTrue(c.validationErrors().contains("Model is required"));
    }

    @Test
    void validationRejectsANonHttpBaseUrl() {
        var c = LlmEndpointConfig.of("x", Api.ANTHROPIC, "gw.corp", "m");
        assertTrue(c.validationErrors().stream().anyMatch(e -> e.contains("http://")));
    }

    @Test
    void oidcRequiresTokenUrlClientIdAndSecret() {
        var c = new LlmEndpointConfig(null, "x", Api.ANTHROPIC, "https://gw.corp", null,
                Auth.OIDC_CLIENT_CREDENTIALS, null, null, null, null, null, null, null, null,
                Map.of(), "m", 0, null, null, null, List.of(), null, 0, 0);
        var errors = c.validationErrors();
        assertTrue(errors.contains("Token URL is required for OIDC"), errors.toString());
        assertTrue(errors.contains("Client ID is required for OIDC"), errors.toString());
        assertTrue(errors.contains("Client secret is required for OIDC"), errors.toString());
    }

    @Test
    void aFullyConfiguredOidcEndpointValidates() {
        assertTrue(config(Api.ANTHROPIC, "https://gw.corp", Auth.OIDC_CLIENT_CREDENTIALS)
                .validationErrors().isEmpty());
    }

    @Test
    void anUnsetEnvSecretFailsValidationRatherThanBeingSent() {
        var c = new LlmEndpointConfig(null, "x", Api.ANTHROPIC, "https://gw.corp", null, Auth.API_KEY,
                null, "${NEXUSLINK_DEFINITELY_UNSET_VAR}", null, null, null, null, null, null,
                Map.of(), "m", 0, null, null, null, List.of(), null, 0, 0);
        assertTrue(c.validationErrors().stream().anyMatch(e -> e.contains("API key")));
    }
}
