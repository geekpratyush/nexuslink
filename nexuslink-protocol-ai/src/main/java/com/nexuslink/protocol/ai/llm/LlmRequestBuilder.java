package com.nexuslink.protocol.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the JSON request body for an {@link LlmEndpointConfig}, in whichever wire format the
 * endpoint speaks. Pure and dependency-light so the exact bytes that will go on the wire can be
 * asserted in tests — the thing that is otherwise impossible to check without a live endpoint.
 *
 * <p>Only parameters the user actually set are emitted. Sending {@code "temperature": null} or a
 * silently-invented default is a real source of confusion against strict internal gateways, which
 * often reject unknown or null fields outright.
 */
public final class LlmRequestBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmRequestBuilder() {}

    /** Renders the request body for a single-turn completion. */
    public static String body(LlmEndpointConfig config, String systemPrompt, String userMessage) {
        return switch (config.api()) {
            case ANTHROPIC -> anthropic(config, systemPrompt, userMessage);
            case OPENAI -> openai(config, systemPrompt, userMessage);
        };
    }

    private static String anthropic(LlmEndpointConfig c, String system, String user) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", c.model());
        root.put("max_tokens", c.maxTokens());

        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", user == null ? "" : user);

        // Anthropic takes the system prompt as a top-level field, not as a message.
        if (notBlank(system)) root.put("system", system);
        if (c.temperature() != null) root.put("temperature", c.temperature());
        if (c.topP() != null) root.put("top_p", c.topP());
        if (c.topK() != null) root.put("top_k", c.topK());
        if (!c.stopSequences().isEmpty()) {
            ArrayNode stop = root.putArray("stop_sequences");
            c.stopSequences().forEach(stop::add);
        }
        if (c.thinkingBudgetTokens() != null) {
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", c.thinkingBudgetTokens());
        }
        return write(root);
    }

    private static String openai(LlmEndpointConfig c, String system, String user) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", c.model());
        root.put("max_tokens", c.maxTokens());

        ArrayNode messages = root.putArray("messages");
        // OpenAI-format APIs carry the system prompt as the first message in the list.
        if (notBlank(system)) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", user == null ? "" : user);

        if (c.temperature() != null) root.put("temperature", c.temperature());
        if (c.topP() != null) root.put("top_p", c.topP());
        // top_k is not part of the OpenAI schema; sending it would 400 on a strict gateway.
        if (!c.stopSequences().isEmpty()) {
            ArrayNode stop = root.putArray("stop");
            c.stopSequences().forEach(stop::add);
        }
        return write(root);
    }

    /**
     * The headers to send with the request, excluding {@code Authorization} — which
     * {@link HttpLlmClient} adds once it has resolved the credential.
     */
    public static Map<String, String> headers(LlmEndpointConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        if (config.api() == LlmEndpointConfig.Api.ANTHROPIC) {
            headers.put("anthropic-version", config.anthropicVersion());
        }
        // User headers last so an org's gateway requirements can override our defaults.
        headers.putAll(config.extraHeaders());
        return headers;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to render the request body", e);
        }
    }
}
