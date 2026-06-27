package com.nexuslink.protocol.http.rest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Executes {@link RestRequest}s using the JDK's built-in HTTP client
 * (HTTP/2 capable, zero external dependencies for the first cut).
 * <p>
 * Blocking by design — callers run this off the UI thread (JavaFX Task).
 * Timing is a simplified waterfall: a real DNS/TCP/TLS breakdown will come with
 * the OkHttp/EventListener integration noted in TASKS.md §3.1.
 */
public final class RestExecutionService {

    /** Executes the request and returns a populated {@link RestResponse}. */
    public RestResponse execute(RestRequest req) {
        long start = System.nanoTime();
        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(req.getConnectTimeoutMs()))
                    .followRedirects(req.isFollowRedirects()
                            ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
            // Custom TLS material (CA trust store and/or client cert for mTLS), when configured.
            if (req.tlsConfig().isCustom()) {
                clientBuilder.sslContext(com.nexuslink.security.tls.TlsContextFactory.create(req.tlsConfig()));
            }
            HttpClient client = clientBuilder.build();

            long sendStart = System.nanoTime();
            HttpResponse<String> resp = client.send(buildRequest(req, null),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // Digest is challenge-response: on a 401 with a Digest challenge, compute the
            // Authorization from the server's nonce and retry once.
            if (req.getAuthType() == RestRequest.AuthType.DIGEST && resp.statusCode() == 401) {
                String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
                if (challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                    String authHeader = DigestAuthenticator.authorization(
                            DigestAuthenticator.parseChallenge(challenge),
                            req.getAuthUsername(), req.getAuthPassword(),
                            req.getMethod().toUpperCase(), URI.create(req.requestUri()).getRawPath());
                    resp = client.send(buildRequest(req, authHeader),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                }
            }
            long now = System.nanoTime();

            long totalMs = nanosToMs(now - start);
            long ttfbMs = nanosToMs(sendStart - start);   // setup ≈ DNS+TCP+TLS
            long downloadMs = nanosToMs(now - sendStart);

            String body = resp.body();
            long bytes = body.getBytes(StandardCharsets.UTF_8).length;

            RestResponse.Timing timing = new RestResponse.Timing(
                    0, ttfbMs, 0, ttfbMs, downloadMs, totalMs);

            return new RestResponse(
                    resp.statusCode(),
                    statusText(resp.statusCode()),
                    resp.headers().map(),
                    body,
                    bytes,
                    resp.version().toString(),
                    timing,
                    false,
                    null);

        } catch (Exception e) {
            long totalMs = nanosToMs(System.nanoTime() - start);
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "(no detail)" : e.getMessage());
            return RestResponse.error(msg, totalMs);
        }
    }

    /**
     * Builds the JDK {@link HttpRequest} including body, headers, and auth. {@code digestAuthHeader}
     * is the precomputed Digest {@code Authorization} value for a retry (null on the first attempt).
     */
    private HttpRequest buildRequest(RestRequest req, String digestAuthHeader) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(req.requestUri()))
                .timeout(Duration.ofMillis(req.getReadTimeoutMs()));

        byte[] bodyBytes = req.getBodyType() == RestRequest.BodyType.NONE
                ? new byte[0] : req.getBody().getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher publisher = req.getBodyType() == RestRequest.BodyType.NONE
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(bodyBytes);
        builder.method(req.getMethod().toUpperCase(), publisher);

        // Content-Type from body type (unless user set one explicitly)
        String ct = req.contentType();
        boolean userSetCt = req.getHeaders().stream()
                .anyMatch(kv -> kv.isEnabled() && kv.getKey().equalsIgnoreCase("Content-Type"));
        if (ct != null && !userSetCt) builder.header("Content-Type", ct);

        // User headers
        for (RestRequest.KeyValue kv : req.getHeaders()) {
            if (kv.isEnabled() && !kv.getKey().isBlank()) {
                safeHeader(builder, kv.getKey(), kv.getValue());
            }
        }

        // Auth
        switch (req.getAuthType()) {
            case BASIC -> {
                String token = Base64.getEncoder().encodeToString(
                        (req.getAuthUsername() + ":" + req.getAuthPassword())
                                .getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + token);
            }
            case BEARER -> builder.header("Authorization", "Bearer " + req.getAuthToken());
            case API_KEY -> {
                // QUERY placement is folded into requestUri(); only HEADER is applied here.
                if (req.getApiKeyLocation() == RestRequest.ApiKeyLocation.HEADER
                        && !req.getApiKeyName().isBlank()) {
                    safeHeader(builder, req.getApiKeyName(), req.getApiKeyValue());
                }
            }
            case OAUTH2 -> {
                try {
                    String token = OAuth2TokenClient.accessToken(req.getOauthTokenUrl(),
                            req.getOauthClientId(), req.getOauthClientSecret(), req.getOauthScope());
                    builder.header("Authorization", "Bearer " + token);
                } catch (Exception e) {
                    throw new RuntimeException("OAuth2 token fetch failed: " + e.getMessage(), e);
                }
            }
            case AWS_SIGV4 -> {
                var signed = AwsSigV4Signer.sign(req.getMethod(), req.requestUri(),
                        req.getAwsRegion(), req.getAwsService(),
                        req.getAwsAccessKey(), req.getAwsSecretKey(), req.getAwsSessionToken(),
                        java.util.Map.of(), bodyBytes, java.time.Instant.now());
                signed.forEach((k, v) -> safeHeader(builder, k, v));
            }
            case DIGEST -> {
                // First attempt sends no auth (to obtain the challenge); the retry passes the header.
                if (digestAuthHeader != null) builder.header("Authorization", digestAuthHeader);
            }
            case NONE -> { /* no auth header */ }
        }
        return builder.build();
    }

    private void safeHeader(HttpRequest.Builder b, String name, String value) {
        try {
            b.header(name, value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            // restricted header (e.g. Host, Connection) — skip silently
        }
    }

    private static long nanosToMs(long nanos) {
        return Math.round(nanos / 1_000_000.0);
    }

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }
}
