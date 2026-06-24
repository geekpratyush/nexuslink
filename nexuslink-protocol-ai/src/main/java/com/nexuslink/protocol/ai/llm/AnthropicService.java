package com.nexuslink.protocol.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;

import java.util.List;

/**
 * Thin wrapper over the official Anthropic Java SDK for the "AI Agent / LLM" tester panel.
 * Defaults follow Anthropic's current guidance: model {@code claude-opus-4-8} with
 * adaptive thinking. The API key is read from the {@code ANTHROPIC_API_KEY} environment
 * variable; when absent, {@link #isConfigured()} returns false and the UI prompts for it.
 */
public final class AnthropicService {

    /** Models offered in the tester (latest Claude family first). */
    public static final List<String> MODELS = List.of(
            "claude-opus-4-8",
            "claude-sonnet-4-6",
            "claude-haiku-4-5",
            "claude-fable-5");

    public static final String DEFAULT_MODEL = "claude-opus-4-8";
    private static final long DEFAULT_MAX_TOKENS = 16_000L;

    /** True when an API key is available in the environment. */
    public boolean isConfigured() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    /**
     * Sends a single-turn Messages API request and returns the assistant's text plus usage.
     * Runs on a background thread — never call from the UI thread.
     */
    public Result complete(String model, String systemPrompt, String userMessage) {
        if (!isConfigured()) {
            return Result.error("ANTHROPIC_API_KEY is not set. Export it and reconnect to test live calls.");
        }
        long start = System.nanoTime();
        try {
            AnthropicClient client = AnthropicOkHttpClient.fromEnv();

            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                    .model(model == null || model.isBlank() ? DEFAULT_MODEL : model)
                    .maxTokens(DEFAULT_MAX_TOKENS)
                    // Adaptive thinking is the recommended mode for Claude 4.6+ models.
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .addUserMessage(userMessage);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                builder.system(systemPrompt);
            }

            Message response = client.messages().create(builder.build());

            StringBuilder text = new StringBuilder();
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
            }

            long inputTokens = response.usage().inputTokens();
            long outputTokens = response.usage().outputTokens();
            String stopReason = response.stopReason().map(Object::toString).orElse("");
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);

            return new Result(true, text.toString(), inputTokens, outputTokens, stopReason, ms, null);
        } catch (Exception e) {
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "(no detail)" : e.getMessage());
            return new Result(false, "", 0, 0, "", ms, msg);
        }
    }

    /** Result of a completion: text + token usage, or an error message. */
    public record Result(
            boolean success,
            String text,
            long inputTokens,
            long outputTokens,
            String stopReason,
            long durationMs,
            String error
    ) {
        static Result error(String message) {
            return new Result(false, "", 0, 0, "", 0, message);
        }
    }
}
