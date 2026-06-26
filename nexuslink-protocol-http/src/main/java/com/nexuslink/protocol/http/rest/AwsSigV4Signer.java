package com.nexuslink.protocol.http.rest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 request signer (the "Authorization header" variant). Pure and
 * side-effect-free: given a request's method/URI/headers/payload plus credentials, it returns the
 * headers to add ({@code Authorization}, {@code X-Amz-Date}, and {@code X-Amz-Security-Token} for
 * temporary credentials). Verified against the official {@code aws-sig-v4-test-suite} known-answer
 * vectors, so the canonical-request/string-to-sign/signing-key chain is exercised offline.
 *
 * <p>Only the host and {@code x-amz-date} (plus any caller-supplied headers) are signed — matching
 * the test-suite's canonical behaviour; callers that need {@code x-amz-content-sha256} signed (e.g.
 * S3) can pass it in as an extra header.
 */
public final class AwsSigV4Signer {

    private AwsSigV4Signer() {}

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /**
     * Computes the SigV4 headers for a request. {@code extraHeaders} are additional headers to sign
     * (lower-cased, trimmed); {@code sessionToken} may be null/blank for long-term credentials.
     */
    public static Map<String, String> sign(String method, String uri, String region, String service,
                                           String accessKey, String secretKey, String sessionToken,
                                           Map<String, String> extraHeaders, byte[] payload, Instant when) {
        URI u = URI.create(uri);
        String host = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        String amzDate = AMZ_DATE.format(when);
        String dateStamp = DATE_STAMP.format(when);
        byte[] body = payload == null ? new byte[0] : payload;
        String payloadHash = hex(sha256(body));

        // Canonical headers (sorted, lower-cased name, trimmed value): host + x-amz-date + extras.
        TreeMap<String, String> signed = new TreeMap<>();
        signed.put("host", host);
        signed.put("x-amz-date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) signed.put("x-amz-security-token", sessionToken);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                signed.put(e.getKey().toLowerCase().trim(), e.getValue() == null ? "" : e.getValue().trim());
            }
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (signedHeaders.length() > 0) signedHeaders.append(';');
            signedHeaders.append(e.getKey());
        }

        String canonicalUri = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        String canonicalQuery = u.getRawQuery() == null ? "" : canonicalizeQuery(u.getRawQuery());

        String canonicalRequest = method.toUpperCase() + '\n'
                + canonicalUri + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;

        String credentialScope = dateStamp + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = ALGORITHM + '\n'
                + amzDate + '\n'
                + credentialScope + '\n'
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = signingKey(secretKey, dateStamp, region, service);
        String signature = hex(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authorization = ALGORITHM
                + " Credential=" + accessKey + '/' + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> out = new LinkedHashMap<>();
        out.put("X-Amz-Date", amzDate);
        if (sessionToken != null && !sessionToken.isBlank()) out.put("X-Amz-Security-Token", sessionToken);
        out.put("Authorization", authorization);
        return out;
    }

    private static String canonicalizeQuery(String rawQuery) {
        // Sort params by name (then value); values are assumed already percent-encoded.
        TreeMap<String, String> params = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(k, v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static byte[] signingKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
                dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmac(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 failure", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
