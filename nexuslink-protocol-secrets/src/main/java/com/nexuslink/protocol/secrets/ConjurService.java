package com.nexuslink.protocol.secrets;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * A thin CyberArk Conjur (OSS / Enterprise) client over the JDK HTTP client. Covers the machine-identity
 * flow an engineer tests: authenticate a host/user with its API key to obtain a short-lived access token,
 * then fetch secret variable values with that token.
 *
 * <p>Conjur's authenticate endpoint returns a raw access token; it is base64-encoded and sent back on
 * every subsequent call as {@code Authorization: Token token="<base64>"}. Path building and encoding live
 * in {@link ConjurPaths} so they can be unit-tested offline. All calls block and run off the UI thread.
 */
public final class ConjurService implements AutoCloseable {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String applianceUrl;  // normalised, no trailing slash
    private String account;
    private String base64Token;   // base64 of the current access token

    public boolean isConnected() { return applianceUrl != null && base64Token != null; }

    public String account() { return account; }

    /**
     * Authenticates {@code login} (e.g. {@code admin} or a host id like {@code host/ci/deployer}) with its
     * {@code apiKey} against {@code account}, stores the returned access token, and returns its base64 form.
     */
    public String authenticate(String applianceUrl, String account, String login, String apiKey) {
        this.applianceUrl = ConjurPaths.normalizeUrl(applianceUrl);
        if (account == null || account.isBlank()) throw new IllegalArgumentException("account is required");
        this.account = account.trim();
        this.base64Token = null;

        String url = this.applianceUrl + "/" + ConjurPaths.authenticatePath(this.account, login);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/plain")
                .header("Accept-Encoding", "base64")   // ask Conjur to return the token already base64-encoded
                .POST(HttpRequest.BodyPublishers.ofString(apiKey == null ? "" : apiKey, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req, "authenticate");
        String body = resp.body() == null ? "" : resp.body().trim();
        if (body.isEmpty()) throw new ConjurException("authenticate returned an empty token");
        // With Accept-Encoding: base64 the body IS the base64 token; otherwise it's the raw token JSON we encode.
        this.base64Token = looksBase64(body) ? body
                : Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return this.base64Token;
    }

    /** Reads the string value of a {@code variable} secret by its identifier (e.g. {@code nexus/db/password}). */
    public String getSecret(String variableId) {
        requireToken();
        String url = applianceUrl + "/" + ConjurPaths.secretPath(account, variableId);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", ConjurPaths.authorizationHeader(base64Token))
                .GET()
                .build();
        return send(req, "getSecret").body();
    }

    /** Basic reachability probe: Conjur's unauthenticated {@code /info} (OSS) / health endpoint returns 200. */
    public boolean ping(String applianceUrl) {
        String base = ConjurPaths.normalizeUrl(applianceUrl);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/info"))
                .timeout(Duration.ofSeconds(10)).GET().build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void requireToken() {
        if (base64Token == null) throw new IllegalStateException("not authenticated");
    }

    private HttpResponse<String> send(HttpRequest req, String op) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ConjurException("Conjur " + op + " failed: " + e.getMessage(), e);
        }
        int sc = resp.statusCode();
        if (sc < 200 || sc >= 300) {
            String b = resp.body();
            throw new ConjurException("Conjur " + op + " -> HTTP " + sc
                    + (b == null || b.isBlank() ? "" : ": " + b.strip()));
        }
        return resp;
    }

    private static boolean looksBase64(String s) {
        // A raw Conjur token is JSON (starts with '{'); a base64 token is not and decodes cleanly.
        if (s.startsWith("{") || s.startsWith("[")) return false;
        try {
            Base64.getDecoder().decode(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void close() {
        applianceUrl = null;
        account = null;
        base64Token = null;
    }

    /** Unchecked wrapper for any Conjur transport/API error. */
    public static final class ConjurException extends RuntimeException {
        public ConjurException(String message) { super(message); }
        public ConjurException(String message, Throwable cause) { super(message, cause); }
    }
}
