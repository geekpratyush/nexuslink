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
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(req.getConnectTimeoutMs()))
                    .followRedirects(req.isFollowRedirects()
                            ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                    .build();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(req.effectiveUrl()))
                    .timeout(Duration.ofMillis(req.getReadTimeoutMs()));

            // Body / method
            HttpRequest.BodyPublisher publisher = req.getBodyType() == RestRequest.BodyType.NONE
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(req.getBody(), StandardCharsets.UTF_8);
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
                case NONE -> { /* no auth header */ }
            }

            long sendStart = System.nanoTime();
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
