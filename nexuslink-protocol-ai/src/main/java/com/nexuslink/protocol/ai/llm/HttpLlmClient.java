package com.nexuslink.protocol.ai.llm;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Calls any {@link LlmEndpointConfig} over plain HTTP — the path used for internal gateways and
 * self-hosted models, where the vendor SDK's assumptions about hostnames and API keys don't hold.
 *
 * <p>Requests honour the JVM proxy settings; a gateway behind a private CA needs that CA in the
 * JVM trust store ({@code -Djavax.net.ssl.trustStore=…}), the same as everywhere else in the app.
 */
public final class HttpLlmClient {

    private HttpLlmClient() {}

    /**
     * Sends a single-turn completion and returns the assistant's text plus usage. Never throws —
     * failures come back as a failed {@link AnthropicService.Result}, because every caller is a UI
     * panel that wants to render the error rather than handle an exception.
     *
     * <p>Runs synchronously; call it off the UI thread.
     */
    public static AnthropicService.Result complete(LlmEndpointConfig config, String systemPrompt,
                                                   String userMessage) {
        var problems = config.validationErrors();
        if (!problems.isEmpty()) {
            return AnthropicService.Result.error("Endpoint isn't configured: " + String.join("; ", problems));
        }

        long start = System.nanoTime();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.requestUrl()))
                    .timeout(Duration.ofMillis(config.readTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            LlmRequestBuilder.body(config, systemPrompt, userMessage)));

            for (Map.Entry<String, String> header : LlmRequestBuilder.headers(config).entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
            applyAuth(config, request);

            HttpClient http = HttpClient.newBuilder()
                    .proxy(ProxySelector.getDefault())
                    .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            long ms = millisSince(start);

            if (response.statusCode() / 100 != 2) {
                return new AnthropicService.Result(false, "", 0, 0, "", ms,
                        LlmResponseParser.errorMessage(response.statusCode(), response.body()));
            }
            return LlmResponseParser.parse(config.api(), response.body(), ms);

        } catch (OidcTokenSource.LlmException e) {
            return new AnthropicService.Result(false, "", 0, 0, "", millisSince(start), e.getMessage());
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new AnthropicService.Result(false, "", 0, 0, "", millisSince(start),
                    "Request failed: " + detail);
        }
    }

    /** Adds the credential header for the endpoint's auth mode. */
    private static void applyAuth(LlmEndpointConfig config, HttpRequest.Builder request) {
        switch (config.auth()) {
            case NONE -> { /* the gateway authenticates some other way */ }
            case API_KEY -> request.header(config.apiKeyHeader(),
                    LlmEndpointConfig.resolve(config.apiKey()));
            case BEARER -> request.header("Authorization",
                    "Bearer " + LlmEndpointConfig.resolve(config.bearerToken()));
            case OIDC_CLIENT_CREDENTIALS -> request.header("Authorization", "Bearer "
                    + OidcTokenSource.accessToken(config.tokenUrl(), config.clientId(),
                    LlmEndpointConfig.resolve(config.clientSecret()), config.scope(),
                    config.connectTimeoutMs()));
        }
    }

    private static long millisSince(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }
}
