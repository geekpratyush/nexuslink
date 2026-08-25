package com.nexuslink.protocol.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches OIDC / OAuth2 access tokens with the client-credentials grant — the flow an internal
 * service-to-service API is normally protected by — and caches them until shortly before they
 * expire.
 *
 * <p>Credentials go in the request body rather than a {@code Basic} header by default, because
 * that is what most enterprise identity providers (Entra ID, Ping, Keycloak) accept for a
 * confidential client; providers that require basic auth get it too, since the header is sent
 * alongside. The exchange honours the JVM proxy settings, which a desktop tool inside a corporate
 * network invariably needs.
 */
public final class OidcTokenSource {

    private record CachedToken(String accessToken, Instant expiresAt) {}

    /** Cached per (tokenUrl, clientId, scope) — never keyed on the secret. */
    private static final Map<String, CachedToken> CACHE = new ConcurrentHashMap<>();

    /** Refresh this long before actual expiry, so a token can't expire mid-request. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OidcTokenSource() {}

    /**
     * Returns a valid access token, reusing a cached one when it has not expired.
     *
     * @throws LlmException if the identity provider rejects the exchange or is unreachable
     */
    public static String accessToken(String tokenUrl, String clientId, String clientSecret,
                                     String scope, int connectTimeoutMs) {
        String key = tokenUrl + "|" + clientId + "|" + (scope == null ? "" : scope);
        CachedToken cached = CACHE.get(key);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.accessToken();

        StringBuilder form = new StringBuilder("grant_type=client_credentials")
                .append("&client_id=").append(encode(clientId))
                .append("&client_secret=").append(encode(clientSecret));
        if (scope != null && !scope.isBlank()) form.append("&scope=").append(encode(scope));

        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + basic)
                .timeout(Duration.ofMillis(Math.max(1_000, connectTimeoutMs)))
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();

        HttpClient http = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofMillis(Math.max(1_000, connectTimeoutMs)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new LlmException("Could not reach the OIDC token endpoint " + tokenUrl + ": "
                    + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new LlmException("OIDC token request failed — "
                    + LlmResponseParser.errorMessage(response.statusCode(), response.body()), null);
        }

        try {
            JsonNode root = MAPPER.readTree(response.body());
            String token = root.path("access_token").asText("");
            if (token.isBlank()) {
                throw new LlmException("The OIDC response contained no access_token", null);
            }
            long expiresIn = root.path("expires_in").asLong(3600);
            CACHE.put(key, new CachedToken(token,
                    Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN)));
            return token;
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Could not parse the OIDC token response: " + e.getMessage(), e);
        }
    }

    /** Drops every cached token — used when credentials change so the next call re-authenticates. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** Raised when an endpoint or its identity provider can't be used. */
    public static final class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
