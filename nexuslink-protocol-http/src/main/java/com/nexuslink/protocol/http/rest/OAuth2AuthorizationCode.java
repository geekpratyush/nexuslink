package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth 2.0 <em>Authorization Code</em> flow with PKCE (RFC 6749 §4.1 + RFC 7636). The pure pieces —
 * PKCE pair generation, the {@code S256} challenge derivation, building the authorization-request
 * URL, and parsing the redirect callback — are static and fully unit-tested; only {@link
 * #exchangeCode} performs live network I/O against the token endpoint.
 *
 * <p>Typical use: {@link #createPkce()} → {@link #buildAuthorizationUrl} (open in a browser) → user
 * approves → the redirect carries {@code code} + {@code state}, parsed by {@link #parseRedirect} →
 * {@link #exchangeCode} swaps the code (plus the PKCE verifier) for tokens.
 */
public final class OAuth2AuthorizationCode {

    private OAuth2AuthorizationCode() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /** A PKCE code verifier + its derived challenge (always {@code S256} here). */
    public record Pkce(String verifier, String challenge, String method) {}

    /** The result of parsing the authorization redirect: a {@code code}+{@code state}, or an error. */
    public record AuthCodeResult(String code, String state, String error, String errorDescription) {
        public boolean isError() { return error != null && !error.isBlank(); }
    }

    /** Tokens returned by the token endpoint. */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType,
                                long expiresIn, String scope, String raw) {}

    /** Generates a fresh PKCE pair: a 43-char base64url verifier and its SHA-256 ({@code S256}) challenge. */
    public static Pkce createPkce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String verifier = B64URL.encodeToString(bytes);
        return new Pkce(verifier, codeChallengeS256(verifier), "S256");
    }

    /** Derives the {@code S256} code challenge: base64url(sha256(ASCII(verifier))), unpadded (RFC 7636). */
    public static String codeChallengeS256(String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return B64URL.encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Builds the authorization-request URL. Query params already present on {@code authEndpoint} are
     * preserved. {@code scope}/{@code state} may be blank; {@code pkce} may be null to omit PKCE.
     */
    public static String buildAuthorizationUrl(String authEndpoint, String clientId, String redirectUri,
                                               String scope, String state, Pkce pkce) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        if (redirectUri != null && !redirectUri.isBlank()) params.put("redirect_uri", redirectUri);
        if (scope != null && !scope.isBlank()) params.put("scope", scope);
        if (state != null && !state.isBlank()) params.put("state", state);
        if (pkce != null) {
            params.put("code_challenge", pkce.challenge());
            params.put("code_challenge_method", pkce.method());
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        char sep = authEndpoint.contains("?") ? '&' : '?';
        return authEndpoint + sep + query;
    }

    /** Parses an authorization redirect URL (or a bare query string) into a {@link AuthCodeResult}. */
    public static AuthCodeResult parseRedirect(String redirectUrl) {
        String query = redirectUrl == null ? "" : redirectUrl;
        int q = query.indexOf('?');
        if (q >= 0) query = query.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);

        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(dec(k), dec(v));
        }
        return new AuthCodeResult(
                params.get("code"), params.get("state"),
                params.get("error"), params.get("error_description"));
    }

    /**
     * Exchanges an authorization {@code code} (plus the PKCE {@code codeVerifier}) for tokens at
     * {@code tokenUrl}. {@code clientSecret} may be blank for public clients (PKCE-only). Live I/O.
     */
    public static TokenResponse exchangeCode(String tokenUrl, String clientId, String clientSecret,
                                             String redirectUri, String code, String codeVerifier)
            throws Exception {
        StringBuilder form = new StringBuilder("grant_type=authorization_code")
                .append("&code=").append(enc(code))
                .append("&client_id=").append(enc(clientId));
        if (redirectUri != null && !redirectUri.isBlank()) form.append("&redirect_uri=").append(enc(redirectUri));
        if (codeVerifier != null && !codeVerifier.isBlank()) form.append("&code_verifier=").append(enc(codeVerifier));

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        // Confidential clients authenticate with HTTP Basic; public (PKCE) clients send only client_id.
        if (clientSecret != null && !clientSecret.isBlank()) {
            String basic = Base64.getEncoder().encodeToString(
                    (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + basic);
        }
        request.POST(HttpRequest.BodyPublishers.ofString(form.toString()));

        HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Token endpoint returned HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }
        return parseTokenResponse(resp.body());
    }

    /** Parses a token-endpoint JSON body into a {@link TokenResponse}. Pure — unit-tested. */
    public static TokenResponse parseTokenResponse(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        String token = node.path("access_token").asText(null);
        if (token == null) throw new IllegalStateException("No access_token in token response");
        return new TokenResponse(
                token,
                node.path("refresh_token").asText(""),
                node.path("token_type").asText("Bearer"),
                node.path("expires_in").asLong(3600),
                node.path("scope").asText(""),
                json);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
