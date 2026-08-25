package com.nexuslink.protocol.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts the assistant's text, token usage and stop reason from either wire format, and turns an
 * error payload into a readable message.
 *
 * <p>Deliberately lenient: an internal gateway frequently wraps or trims the vendor's response, so
 * a missing usage block or an unexpected content shape yields zeros and empty text rather than an
 * exception. A hard failure here would be indistinguishable from the endpoint being down, which is
 * exactly the confusion this panel exists to remove.
 */
public final class LlmResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmResponseParser() {}

    /** Parses a successful (HTTP 200) response body. */
    public static AnthropicService.Result parse(LlmEndpointConfig.Api api, String body, long durationMs) {
        try {
            JsonNode root = MAPPER.readTree(body);
            return switch (api) {
                case ANTHROPIC -> anthropic(root, durationMs);
                case OPENAI -> openai(root, durationMs);
            };
        } catch (Exception e) {
            return new AnthropicService.Result(false, "", 0, 0, "", durationMs,
                    "Could not parse the response: " + e.getMessage());
        }
    }

    private static AnthropicService.Result anthropic(JsonNode root, long durationMs) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            // Skip thinking blocks: the panel shows the answer, not the reasoning trace.
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        }
        JsonNode usage = root.path("usage");
        return new AnthropicService.Result(true, text.toString(),
                usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong(),
                root.path("stop_reason").asText(""), durationMs, null);
    }

    private static AnthropicService.Result openai(JsonNode root, long durationMs) {
        JsonNode choice = root.path("choices").path(0);
        String text = choice.path("message").path("content").asText("");
        JsonNode usage = root.path("usage");
        return new AnthropicService.Result(true, text,
                usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong(),
                choice.path("finish_reason").asText(""), durationMs, null);
    }

    /**
     * Renders a non-2xx response into a message worth showing a user: the vendor's own error text
     * when the body is the expected JSON shape, otherwise the raw body, truncated.
     */
    public static String errorMessage(int statusCode, String body) {
        String detail = extractErrorDetail(body);
        String hint = switch (statusCode) {
            case 401, 403 -> "  Check the credential — for OIDC, confirm the token URL, client id "
                    + "and scope grant access to this endpoint.";
            case 404 -> "  Check the base URL and path; an internal gateway often mounts the API "
                    + "somewhere other than /v1/messages.";
            case 429 -> "  Rate limited by the endpoint.";
            default -> "";
        };
        return "HTTP " + statusCode + (detail.isBlank() ? "" : ": " + detail) + hint;
    }

    private static String extractErrorDetail(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");
            // Both formats nest a human-readable message under "error"; some gateways use "message".
            String message = error.path("message").asText(
                    error.isTextual() ? error.asText() : root.path("message").asText(""));
            if (!message.isBlank()) return message;
        } catch (Exception ignored) {
            // Not JSON — fall through and show the raw body.
        }
        String trimmed = body.strip();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) + "…" : trimmed;
    }
}
