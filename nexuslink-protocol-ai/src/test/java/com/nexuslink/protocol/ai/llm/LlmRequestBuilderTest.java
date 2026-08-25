package com.nexuslink.protocol.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Api;
import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Auth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmRequestBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static LlmEndpointConfig full(Api api) {
        return new LlmEndpointConfig(null, "n", api, "https://gw.corp", null, Auth.NONE, null,
                null, null, null, null, null, null, null,
                Map.of("X-Tenant", "acme"), "model-x", 2048,
                0.3, 0.9, 40, List.of("STOP", "END"), 1024, 0, 0);
    }

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void anthropicBodyCarriesSystemAtTopLevelAndEveryParameter() throws Exception {
        JsonNode body = parse(LlmRequestBuilder.body(full(Api.ANTHROPIC), "be terse", "hello"));

        assertEquals("model-x", body.get("model").asText());
        assertEquals(2048, body.get("max_tokens").asLong());
        assertEquals("be terse", body.get("system").asText(), "Anthropic takes system top-level");
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertEquals("hello", body.get("messages").get(0).get("content").asText());
        assertEquals(0.3, body.get("temperature").asDouble(), 1e-9);
        assertEquals(0.9, body.get("top_p").asDouble(), 1e-9);
        assertEquals(40, body.get("top_k").asInt());
        assertEquals(List.of("STOP", "END"),
                MAPPER.convertValue(body.get("stop_sequences"), List.class));
        assertEquals("enabled", body.get("thinking").get("type").asText());
        assertEquals(1024, body.get("thinking").get("budget_tokens").asInt());
    }

    @Test
    void openAiBodyCarriesSystemAsTheFirstMessage() throws Exception {
        JsonNode body = parse(LlmRequestBuilder.body(full(Api.OPENAI), "be terse", "hello"));

        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("be terse", body.get("messages").get(0).get("content").asText());
        assertEquals("user", body.get("messages").get(1).get("role").asText());
        assertNull(body.get("system"));
        assertEquals(List.of("STOP", "END"), MAPPER.convertValue(body.get("stop"), List.class),
                "OpenAI names the field 'stop'");
        assertNull(body.get("top_k"), "top_k is not in the OpenAI schema and would 400 a strict gateway");
        assertNull(body.get("thinking"));
    }

    @Test
    void unsetParametersAreOmittedEntirely() throws Exception {
        var minimal = LlmEndpointConfig.of("n", Api.ANTHROPIC, "https://gw.corp", "m");
        JsonNode body = parse(LlmRequestBuilder.body(minimal, null, "hi"));

        assertNull(body.get("temperature"));
        assertNull(body.get("top_p"));
        assertNull(body.get("top_k"));
        assertNull(body.get("stop_sequences"));
        assertNull(body.get("thinking"));
        assertNull(body.get("system"), "a blank system prompt is omitted, not sent empty");
        assertTrue(body.has("model") && body.has("max_tokens") && body.has("messages"));
    }

    @Test
    void anthropicHeadersIncludeTheApiVersion() {
        var headers = LlmRequestBuilder.headers(full(Api.ANTHROPIC));
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals(LlmEndpointConfig.DEFAULT_ANTHROPIC_VERSION, headers.get("anthropic-version"));
        assertEquals("acme", headers.get("X-Tenant"), "gateway routing headers are passed through");
        assertFalse(headers.containsKey("Authorization"), "auth is added by the client, not here");
    }

    @Test
    void openAiHeadersOmitTheAnthropicVersion() {
        assertFalse(LlmRequestBuilder.headers(full(Api.OPENAI)).containsKey("anthropic-version"));
    }

    @Test
    void extraHeadersOverrideTheDefaults() {
        var c = new LlmEndpointConfig(null, "n", Api.ANTHROPIC, "https://gw.corp", null, Auth.NONE,
                null, null, null, null, null, null, null, null,
                Map.of("Accept", "application/x-ndjson"), "m", 0, null, null, null,
                List.of(), null, 0, 0);
        assertEquals("application/x-ndjson", LlmRequestBuilder.headers(c).get("Accept"));
    }

    @Test
    void aNullUserMessageBecomesEmptyRatherThanNull() throws Exception {
        JsonNode body = parse(LlmRequestBuilder.body(
                LlmEndpointConfig.of("n", Api.OPENAI, "https://gw.corp", "m"), null, null));
        assertEquals("", body.get("messages").get(0).get("content").asText());
    }
}
