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

    /**
     * Per-session cookie store. One executor is owned by a single REST client tab,
     * so the jar gives that tab browser-like cookie continuity across requests:
     * {@code Set-Cookie} headers are captured and the matching {@code Cookie}
     * header is replayed automatically on later calls to the same site.
     */
    private final CookieJar cookieJar = new CookieJar();

    /** When false, cookies are neither captured nor sent (the jar is left untouched). */
    private boolean cookieJarEnabled = true;

    /**
     * Distributed-tracing spans captured during this session — one per request whose
     * {@link RestRequest#isTraceEnabled()} injected a {@code traceparent}. Exportable as Zipkin v2
     * JSON via {@link ZipkinSpanExporter}. Guarded by its own monitor (execute runs off the UI thread).
     */
    private final java.util.List<ZipkinSpanExporter.Span> spans = new java.util.ArrayList<>();

    /** The session cookie jar (for a Cookies viewer / inspection). */
    public CookieJar cookieJar() {
        return cookieJar;
    }

    /** Enables or disables automatic cookie capture/injection for this session. */
    public void setCookieJarEnabled(boolean enabled) {
        this.cookieJarEnabled = enabled;
    }

    public boolean isCookieJarEnabled() {
        return cookieJarEnabled;
    }

    /** An immutable snapshot of the spans captured so far this session. */
    public java.util.List<ZipkinSpanExporter.Span> capturedSpans() {
        synchronized (spans) { return java.util.List.copyOf(spans); }
    }

    /** Discards all captured spans (e.g. when the user clears the trace). */
    public void clearSpans() {
        synchronized (spans) { spans.clear(); }
    }

    /** Executes the request and returns a populated {@link RestResponse}. */
    public RestResponse execute(RestRequest req) {
        long start = System.nanoTime();
        long startEpochMicros = System.currentTimeMillis() * 1_000L;
        String traceparent = resolveTraceparent(req);   // null when tracing is off or the user set their own
        String connHost = hostOf(req);
        com.nexuslink.core.event.ConnectionRegistry.global().active("REST@" + connHost, "REST", connHost);
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
            HttpResponse<String> resp = client.send(buildRequest(req, null, traceparent),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            captureCookies(req, resp);

            // Digest is challenge-response: on a 401 with a Digest challenge, compute the
            // Authorization from the server's nonce and retry once.
            if (req.getAuthType() == RestRequest.AuthType.DIGEST && resp.statusCode() == 401) {
                String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
                if (challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                    String authHeader = DigestAuthenticator.authorization(
                            DigestAuthenticator.parseChallenge(challenge),
                            req.getAuthUsername(), req.getAuthPassword(),
                            req.getMethod().toUpperCase(), URI.create(req.requestUri()).getRawPath());
                    resp = client.send(buildRequest(req, authHeader, traceparent),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    captureCookies(req, resp);
                }
            }

            // NTLM is a connection-bound challenge-response: the first request already carried the
            // Type 1 (negotiate) token; on a 401 returning the Type 2 challenge we answer with a Type 3
            // on the same client. (java.net.http pools connections per origin and normally reuses one
            // for these back-to-back requests; a server that strictly pins auth to the exact TCP
            // connection would need a dedicated single-connection transport.)
            if (req.getAuthType() == RestRequest.AuthType.NTLM && resp.statusCode() == 401) {
                String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
                if (challenge.regionMatches(true, 0, "NTLM ", 0, 5)) {
                    NtlmAuthenticator.Type2 type2 =
                            NtlmAuthenticator.parseType2(challenge.substring(5).trim());
                    String type3 = NtlmAuthenticator.type3Message(req.getNtlmDomain(),
                            req.getNtlmUsername(), req.getNtlmPassword(), type2, req.getNtlmWorkstation());
                    resp = client.send(buildRequest(req, "NTLM " + type3, traceparent),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    captureCookies(req, resp);
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

            if (traceparent != null) {
                captureSpan(req, traceparent, startEpochMicros, now - start, resp.statusCode());
            }

            com.nexuslink.core.event.ConnectionRegistry.global().idle("REST@" + connHost, "REST", connHost);
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
            com.nexuslink.core.event.ConnectionRegistry.global().failed("REST@" + connHost, "REST", connHost);
            long totalMs = nanosToMs(System.nanoTime() - start);
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() == null ? "(no detail)" : e.getMessage());
            return RestResponse.error(msg, totalMs);
        }
    }

    /**
     * The {@code traceparent} to send for this request, or null when tracing is off. When the user has
     * already set an explicit {@code traceparent} header we respect theirs (return null → no override).
     */
    private String resolveTraceparent(RestRequest req) {
        if (!req.isTraceEnabled()) return null;
        boolean userSet = req.getHeaders().stream()
                .anyMatch(kv -> kv.isEnabled() && kv.getKey().equalsIgnoreCase(TraceContext.TRACEPARENT)
                        && !kv.getValue().isBlank());
        if (userSet) return null;
        return TraceContext.newRootTraceparent(true).formatTraceparent();
    }

    /** Records a Zipkin CLIENT span for a completed traced request. */
    private void captureSpan(RestRequest req, String traceparent, long startEpochMicros,
                             long durationNanos, int statusCode) {
        TraceContext tc = TraceContext.parseTraceparent(traceparent);
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("http.method", req.getMethod().toUpperCase());
        tags.put("http.url", req.effectiveUrl());
        tags.put("http.status_code", Integer.toString(statusCode));
        String path = URI.create(req.requestUri()).getRawPath();
        ZipkinSpanExporter.Span span = new ZipkinSpanExporter.Span(
                tc.traceId(), tc.spanId(), null,
                req.getMethod().toUpperCase() + " " + (path == null || path.isBlank() ? "/" : path),
                ZipkinSpanExporter.Kind.CLIENT,
                startEpochMicros, durationNanos / 1_000L, "nexuslink", tags);
        synchronized (spans) { spans.add(span); }
    }

    /**
     * Builds the JDK {@link HttpRequest} including body, headers, and auth. {@code authorizationOverride}
     * carries a precomputed {@code Authorization} value for a challenge-response retry — the Digest
     * header, or the NTLM Type 3 token — and is null on the first attempt. {@code traceparent} is the
     * W3C header to inject when distributed tracing is on (null to send none).
     */
    private HttpRequest buildRequest(RestRequest req, String authorizationOverride, String traceparent) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(req.requestUri()))
                .timeout(Duration.ofMillis(req.getReadTimeoutMs()));

        if (traceparent != null) builder.header(TraceContext.TRACEPARENT, traceparent);

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
        boolean userSetCookie = false;
        for (RestRequest.KeyValue kv : req.getHeaders()) {
            if (kv.isEnabled() && !kv.getKey().isBlank()) {
                if (kv.getKey().equalsIgnoreCase("Cookie")) userSetCookie = true;
                safeHeader(builder, kv.getKey(), kv.getValue());
            }
        }

        // Session cookies — replay the jar's matching Cookie header unless the user set one.
        if (cookieJarEnabled && !userSetCookie) {
            String cookieHeader = cookieJar.cookieHeaderFor(URI.create(req.requestUri()));
            if (cookieHeader != null) safeHeader(builder, "Cookie", cookieHeader);
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
                if (authorizationOverride != null) builder.header("Authorization", authorizationOverride);
            }
            case NTLM -> {
                // First request carries the Type 1 (negotiate) token; the retry carries the Type 3.
                String header = authorizationOverride != null
                        ? authorizationOverride
                        : "NTLM " + NtlmAuthenticator.type1Message(
                                req.getNtlmDomain(), req.getNtlmWorkstation());
                builder.header("Authorization", header);
            }
            case HMAC -> {
                var headers = HmacAuthenticator.sign(req.getHmacAlgorithm(), req.getHmacSecret(),
                        req.getHmacEncoding(), req.getHmacStringToSign(), req.getHmacHeaderName(),
                        req.getHmacHeaderValue(), req.getHmacKeyId(),
                        req.getMethod(), req.requestUri(), bodyBytes, java.time.Instant.now());
                headers.forEach((k, v) -> safeHeader(builder, k, v));
            }
            case NONE -> { /* no auth header */ }
        }
        return builder.build();
    }

    /** Stores any {@code Set-Cookie} headers from {@code resp} into the session jar. */
    private void captureCookies(RestRequest req, HttpResponse<String> resp) {
        if (!cookieJarEnabled) return;
        var setCookies = resp.headers().allValues("Set-Cookie");
        if (!setCookies.isEmpty()) {
            cookieJar.storeFrom(URI.create(req.requestUri()), setCookies);
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

    /** Best-effort host label for connection-state tracking; falls back to a placeholder. */
    private static String hostOf(RestRequest req) {
        try {
            String h = URI.create(req.requestUri()).getHost();
            return (h == null || h.isBlank()) ? "(unknown)" : h;
        } catch (Exception e) {
            return "(unknown)";
        }
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
