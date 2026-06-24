package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * Minimal OAuth 2.0 <em>client-credentials</em> token provider. Fetches an access token from the
 * token endpoint (HTTP Basic client auth + {@code grant_type=client_credentials}) and caches it
 * until shortly before {@code expires_in}, so repeated requests reuse one token and auto-refresh
 * when it lapses.
 */
public final class OAuth2TokenClient {

    private OAuth2TokenClient() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, CachedToken> CACHE = new ConcurrentHashMap<>();

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean valid() { return Instant.now().isBefore(expiresAt); }
    }

    /** Returns a valid bearer access token, fetching/refreshing as needed. */
    public static String accessToken(String tokenUrl, String clientId, String clientSecret, String scope)
            throws Exception {
        String key = tokenUrl + "|" + clientId + "|" + scope;
        CachedToken cached = CACHE.get(key);
        if (cached != null && cached.valid()) return cached.accessToken();

        String form = "grant_type=client_credentials"
                + (scope == null || scope.isBlank() ? "" : "&scope=" + enc(scope));
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Token endpoint returned HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }
        JsonNode json = MAPPER.readTree(resp.body());
        String token = json.path("access_token").asText(null);
        if (token == null) throw new IllegalStateException("No access_token in token response");
        long expiresIn = json.path("expires_in").asLong(3600);
        Instant expiresAt = Instant.now().plusSeconds(Math.max(30, expiresIn - 30)); // refresh early
        CACHE.put(key, new CachedToken(token, expiresAt));
        return token;
    }

    /** Clears the token cache (e.g. on credential change). */
    public static void clearCache() { CACHE.clear(); }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
