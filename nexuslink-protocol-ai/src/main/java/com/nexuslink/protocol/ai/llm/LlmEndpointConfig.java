package com.nexuslink.protocol.ai.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fully described LLM endpoint: where to send the request, in which wire format, how to
 * authenticate, and every generation parameter to send with it.
 *
 * <h2>Why this exists alongside {@link AnthropicService}</h2>
 * The SDK path is the right default for someone with an {@code ANTHROPIC_API_KEY}. It is the wrong
 * shape for an organisation, where the model is reached through an internal gateway on a private
 * hostname, authenticated with an OIDC token from the company's identity provider rather than a
 * vendor API key, and often behind extra routing headers. This type describes that case without
 * assuming any particular vendor.
 *
 * <h2>Secrets</h2>
 * Secret-bearing fields ({@link #apiKey}, {@link #bearerToken}, {@link #clientSecret}) hold either a
 * literal value typed for the current session or an {@code ${ENV_VAR}} reference resolved at call
 * time by {@link #resolve(String)}. {@link LlmEndpointStore} persists only the reference form —
 * plaintext secrets are never written to disk.
 */
public record LlmEndpointConfig(
        String id,
        String name,
        Api api,
        String baseUrl,
        String path,
        Auth auth,
        String apiKeyHeader,
        String apiKey,
        String bearerToken,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String scope,
        String anthropicVersion,
        Map<String, String> extraHeaders,
        String model,
        long maxTokens,
        Double temperature,
        Double topP,
        Integer topK,
        List<String> stopSequences,
        Integer thinkingBudgetTokens,
        int connectTimeoutMs,
        int readTimeoutMs
) {

    /** The request/response wire format the endpoint speaks. */
    public enum Api {
        /** Anthropic Messages API: {@code POST /v1/messages}. */
        ANTHROPIC("/v1/messages"),
        /** OpenAI-compatible chat completions — what most internal gateways and proxies expose. */
        OPENAI("/v1/chat/completions");

        private final String defaultPath;

        Api(String defaultPath) {
            this.defaultPath = defaultPath;
        }

        public String defaultPath() {
            return defaultPath;
        }
    }

    /** How the endpoint authenticates the caller. */
    public enum Auth {
        /** No credentials — a gateway that authenticates by network position or mTLS. */
        NONE,
        /** A vendor API key in its own header, e.g. {@code x-api-key} or {@code api-key} (Azure). */
        API_KEY,
        /** A token the user already holds, sent as {@code Authorization: Bearer …}. */
        BEARER,
        /**
         * An OIDC / OAuth2 client-credentials exchange against the org's identity provider; the
         * resulting access token is sent as a bearer token and cached until it expires.
         */
        OIDC_CLIENT_CREDENTIALS
    }

    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    public static final long DEFAULT_MAX_TOKENS = 16_000L;

    /** Normalises optional fields so callers never deal with nulls or a trailing-slash base URL. */
    public LlmEndpointConfig {
        api = api == null ? Api.ANTHROPIC : api;
        auth = auth == null ? Auth.NONE : auth;
        baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
        path = path == null || path.isBlank() ? api.defaultPath() : path.trim();
        apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank()
                ? (api == Api.ANTHROPIC ? "x-api-key" : "api-key") : apiKeyHeader.trim();
        anthropicVersion = anthropicVersion == null || anthropicVersion.isBlank()
                ? DEFAULT_ANTHROPIC_VERSION : anthropicVersion.trim();
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(extraHeaders));
        stopSequences = stopSequences == null ? List.of() : List.copyOf(stopSequences);
        maxTokens = maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
        connectTimeoutMs = connectTimeoutMs <= 0 ? 30_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 120_000 : readTimeoutMs;
    }

    /** A minimal endpoint: everything else takes its default. */
    public static LlmEndpointConfig of(String name, Api api, String baseUrl, String model) {
        return new LlmEndpointConfig(null, name, api, baseUrl, null, Auth.NONE, null, null, null,
                null, null, null, null, null, Map.of(), model, DEFAULT_MAX_TOKENS,
                null, null, null, List.of(), null, 0, 0);
    }

    /** The absolute URL requests go to. */
    public String requestUrl() {
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Resolves a possibly-{@code ${ENV_VAR}} value against the environment. A literal value is
     * returned unchanged; an unset variable resolves to empty so the caller reports a missing
     * credential rather than sending the literal text {@code ${VAR}} as a token.
     */
    public static String resolve(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (!(trimmed.startsWith("${") && trimmed.endsWith("}"))) return trimmed;
        String name = trimmed.substring(2, trimmed.length() - 1).trim();
        String fromEnv = System.getenv(name);
        return fromEnv == null ? "" : fromEnv;
    }

    /** True when {@code value} is an {@code ${ENV_VAR}} reference rather than a literal secret. */
    public static boolean isEnvReference(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }

    /** Describes what is missing before this endpoint can be called, or empty when it's ready. */
    public List<String> validationErrors() {
        List<String> errors = new java.util.ArrayList<>();
        if (baseUrl.isBlank()) errors.add("Base URL is required");
        else if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            errors.add("Base URL must start with http:// or https://");
        }
        if (model == null || model.isBlank()) errors.add("Model is required");
        switch (auth) {
            case API_KEY -> {
                if (isBlank(resolve(apiKey))) errors.add("API key is required for API-key auth");
            }
            case BEARER -> {
                if (isBlank(resolve(bearerToken))) errors.add("Bearer token is required");
            }
            case OIDC_CLIENT_CREDENTIALS -> {
                if (isBlank(tokenUrl)) errors.add("Token URL is required for OIDC");
                if (isBlank(clientId)) errors.add("Client ID is required for OIDC");
                if (isBlank(resolve(clientSecret))) errors.add("Client secret is required for OIDC");
            }
            case NONE -> { /* nothing to check */ }
        }
        return List.copyOf(errors);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
