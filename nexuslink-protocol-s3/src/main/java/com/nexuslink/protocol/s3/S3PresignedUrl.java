package com.nexuslink.protocol.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

/**
 * Pure AWS Signature Version 4 <em>query-string</em> presigner for Amazon S3 objects. Given
 * credentials and an object reference, it produces a time-limited, self-authenticating HTTPS URL
 * that a client can use to {@code GET} or {@code PUT} a single object without any further AWS
 * credentials on the wire. No AWS SDK is used: only {@link javax.crypto.Mac} (HmacSHA256),
 * {@link java.security.MessageDigest} (SHA-256) and the standard library.
 *
 * <p>The presigner is side-effect-free and deterministic: the same inputs together with the same
 * {@link Instant} always yield the same URL, which makes it fully unit-testable offline. Callers
 * that do not care about determinism can use the overloads that default the signing time to
 * {@link Instant#now()}.
 *
 * <p><strong>What is signed.</strong> Following the S3 query-string presigning scheme, the only
 * signed header is {@code host} and the payload hash literal is {@code UNSIGNED-PAYLOAD}. All of the
 * authentication material (algorithm, credential scope, date, expiry, signed-header list and — for
 * temporary credentials — the session token) travels in {@code X-Amz-*} query parameters. The
 * resulting {@code X-Amz-Signature} is appended last and is <em>not</em> itself part of the
 * canonical query used to compute the signature.
 *
 * <p><strong>Endpoint styles.</strong> By default the URL uses virtual-hosted-style addressing,
 * {@code https://<bucket>.s3.<region>.amazonaws.com/<key>}. Path-style addressing,
 * {@code https://s3.<region>.amazonaws.com/<bucket>/<key>}, is available via the {@code pathStyle}
 * flag for buckets or tooling that require it.
 *
 * <p>Instances are immutable; the class is a stateless collection of static factory methods plus a
 * {@link Request} parameter object for the full-control path.
 */
public final class S3PresignedUrl {

    private S3PresignedUrl() {}

    /** The fixed SigV4 algorithm identifier. */
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    /** S3's service name in the credential scope. */
    private static final String SERVICE = "s3";
    /** The payload hash used for query-string presigning. */
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    /** Maximum presigned-URL lifetime accepted by S3: seven days. */
    private static final int MAX_EXPIRY_SECONDS = 604_800;

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /**
     * Immutable parameter object describing a presign request. Use the {@link Builder} to construct
     * one. {@code sessionToken} is optional (leave null/blank for long-term credentials);
     * {@code pathStyle} selects path-style over the default virtual-hosted addressing.
     */
    public static final class Request {
        private final String accessKey;
        private final String secretKey;
        private final String sessionToken;
        private final String region;
        private final String bucket;
        private final String objectKey;
        private final String method;
        private final int expirySeconds;
        private final boolean pathStyle;
        private final String endpoint;

        private Request(Builder b) {
            this.accessKey = b.accessKey;
            this.secretKey = b.secretKey;
            this.sessionToken = b.sessionToken;
            this.region = b.region;
            this.bucket = b.bucket;
            this.objectKey = b.objectKey;
            this.method = b.method;
            this.expirySeconds = b.expirySeconds;
            this.pathStyle = b.pathStyle;
            this.endpoint = b.endpoint;
        }

        /** Starts a new builder. */
        public static Builder builder() {
            return new Builder();
        }
    }

    /** Fluent builder for {@link Request}. */
    public static final class Builder {
        private String accessKey;
        private String secretKey;
        private String sessionToken;
        private String region;
        private String bucket;
        private String objectKey;
        private String method = "GET";
        private int expirySeconds = 900;
        private boolean pathStyle;
        private String endpoint;

        /** Sets the AWS access key id (required). */
        public Builder accessKey(String v) { this.accessKey = v; return this; }
        /** Sets the AWS secret access key (required). */
        public Builder secretKey(String v) { this.secretKey = v; return this; }
        /** Sets the optional STS session token for temporary credentials. */
        public Builder sessionToken(String v) { this.sessionToken = v; return this; }
        /** Sets the AWS region, e.g. {@code us-east-1} (required). */
        public Builder region(String v) { this.region = v; return this; }
        /** Sets the S3 bucket name (required). */
        public Builder bucket(String v) { this.bucket = v; return this; }
        /** Sets the object key/path within the bucket (required). */
        public Builder objectKey(String v) { this.objectKey = v; return this; }
        /** Sets the HTTP method; only {@code GET} and {@code PUT} are supported. Defaults to GET. */
        public Builder method(String v) { this.method = v; return this; }
        /** Sets the URL lifetime in seconds, 1..604800. Defaults to 900. */
        public Builder expirySeconds(int v) { this.expirySeconds = v; return this; }
        /** Selects path-style addressing when true; virtual-hosted (default) when false. */
        public Builder pathStyle(boolean v) { this.pathStyle = v; return this; }
        /**
         * Sets a custom S3-compatible endpoint (e.g. {@code https://play.min.io} or
         * {@code http://localhost:4566}). When set, its scheme and host[:port] are used instead of the
         * AWS {@code s3.<region>.amazonaws.com} default — required for MinIO/LocalStack/Wasabi/etc.
         * Leave null/blank for real AWS.
         */
        public Builder endpoint(String v) { this.endpoint = v; return this; }

        /** Builds the immutable request. */
        public Request build() { return new Request(this); }
    }

    /**
     * Presigns a {@code GET} URL for downloading an object, valid for {@code expirySeconds}, signed
     * at {@code signingTime}.
     */
    public static String get(String accessKey, String secretKey, String region, String bucket,
                             String objectKey, int expirySeconds, Instant signingTime) {
        return presign(Request.builder().accessKey(accessKey).secretKey(secretKey).region(region)
                .bucket(bucket).objectKey(objectKey).method("GET").expirySeconds(expirySeconds)
                .build(), signingTime);
    }

    /** {@code GET} presign using the current time as the signing time. */
    public static String get(String accessKey, String secretKey, String region, String bucket,
                             String objectKey, int expirySeconds) {
        return get(accessKey, secretKey, region, bucket, objectKey, expirySeconds, Instant.now());
    }

    /**
     * Presigns a {@code PUT} URL for uploading an object, valid for {@code expirySeconds}, signed at
     * {@code signingTime}.
     */
    public static String put(String accessKey, String secretKey, String region, String bucket,
                             String objectKey, int expirySeconds, Instant signingTime) {
        return presign(Request.builder().accessKey(accessKey).secretKey(secretKey).region(region)
                .bucket(bucket).objectKey(objectKey).method("PUT").expirySeconds(expirySeconds)
                .build(), signingTime);
    }

    /** {@code PUT} presign using the current time as the signing time. */
    public static String put(String accessKey, String secretKey, String region, String bucket,
                             String objectKey, int expirySeconds) {
        return put(accessKey, secretKey, region, bucket, objectKey, expirySeconds, Instant.now());
    }

    /** Full-control presign using the current time as the signing time. */
    public static String presign(Request req) {
        return presign(req, Instant.now());
    }

    /**
     * Presigns a URL from a fully-specified {@link Request} at a caller-supplied signing time. This
     * is the core entry point; the {@code get}/{@code put} helpers delegate here.
     *
     * @param req         the request parameters (must be non-null and valid)
     * @param signingTime the instant used to derive {@code X-Amz-Date} and the credential scope date
     * @return an absolute {@code https://} URL carrying the SigV4 query-string signature
     * @throws IllegalArgumentException if any input is blank, the method is not GET/PUT, or the
     *                                  expiry is outside 1..604800
     */
    public static String presign(Request req, Instant signingTime) {
        if (req == null) throw new IllegalArgumentException("request must not be null");
        if (signingTime == null) throw new IllegalArgumentException("signingTime must not be null");
        String accessKey = require(req.accessKey, "accessKey");
        String secretKey = require(req.secretKey, "secretKey");
        String region = require(req.region, "region");
        String bucket = require(req.bucket, "bucket");
        String objectKey = req.objectKey;
        if (objectKey == null || objectKey.isEmpty()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        String method = req.method == null ? "" : req.method.trim().toUpperCase();
        if (!method.equals("GET") && !method.equals("PUT")) {
            throw new IllegalArgumentException("method must be GET or PUT, was: " + req.method);
        }
        int expiry = req.expirySeconds;
        if (expiry < 1 || expiry > MAX_EXPIRY_SECONDS) {
            throw new IllegalArgumentException(
                    "expirySeconds must be in 1.." + MAX_EXPIRY_SECONDS + ", was: " + expiry);
        }

        String amzDate = AMZ_DATE.format(signingTime);
        String dateStamp = DATE_STAMP.format(signingTime);

        // Host + scheme come from a custom endpoint when supplied, else the AWS default; the canonical
        // URI depends on the addressing style. The signed "host" header must match the URL's authority.
        String scheme = "https";
        String endpointAuthority = null;      // host[:port] parsed from a custom endpoint, if any
        if (req.endpoint != null && !req.endpoint.isBlank()) {
            String raw = req.endpoint.trim();
            int sep = raw.indexOf("://");
            if (sep >= 0) { scheme = raw.substring(0, sep); raw = raw.substring(sep + 3); }
            int slash = raw.indexOf('/');
            if (slash >= 0) raw = raw.substring(0, slash);   // drop any path, keep host[:port]
            endpointAuthority = raw;
        }

        String host;
        String canonicalUri;
        String keyPath = encodePath(objectKey);
        if (req.pathStyle) {
            host = endpointAuthority != null ? endpointAuthority : "s3." + region + ".amazonaws.com";
            canonicalUri = "/" + encodeSegment(bucket) + keyPath;
        } else {
            host = endpointAuthority != null
                    ? bucket + "." + endpointAuthority
                    : bucket + ".s3." + region + ".amazonaws.com";
            canonicalUri = keyPath;
        }

        String credentialScope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";

        // Canonical query: every key/value RFC-3986 encoded (including '/'), sorted by key.
        TreeMap<String, String> params = new TreeMap<>();
        params.put(rfc3986(ALGORITHM_KEY_ALGO), rfc3986(ALGORITHM));
        params.put(rfc3986("X-Amz-Credential"), rfc3986(accessKey + "/" + credentialScope));
        params.put(rfc3986("X-Amz-Date"), rfc3986(amzDate));
        params.put(rfc3986("X-Amz-Expires"), rfc3986(Integer.toString(expiry)));
        if (req.sessionToken != null && !req.sessionToken.isBlank()) {
            params.put(rfc3986("X-Amz-Security-Token"), rfc3986(req.sessionToken));
        }
        params.put(rfc3986("X-Amz-SignedHeaders"), rfc3986("host"));
        String canonicalQuery = joinQuery(params);

        String canonicalHeaders = "host:" + host + "\n";
        String signedHeaders = "host";

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + UNSIGNED_PAYLOAD;

        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = signingKey(secretKey, dateStamp, region);
        String signature = hex(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        return scheme + "://" + host + canonicalUri + "?" + canonicalQuery
                + "&X-Amz-Signature=" + signature;
    }

    /** Query parameter name for the algorithm; extracted to keep the map wiring readable. */
    private static final String ALGORITHM_KEY_ALGO = "X-Amz-Algorithm";

    private static String require(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return v;
    }

    private static String joinQuery(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Encodes a full object path, percent-encoding each segment but preserving the {@code /}. */
    private static String encodePath(String key) {
        String k = key.startsWith("/") ? key.substring(1) : key;
        StringBuilder sb = new StringBuilder();
        for (String segment : k.split("/", -1)) {
            sb.append('/').append(encodeSegment(segment));
        }
        return sb.toString();
    }

    /** RFC 3986 encoding of a single path segment (does not encode '/', but a segment has none). */
    private static String encodeSegment(String segment) {
        return rfc3986(segment);
    }

    /**
     * RFC 3986 percent-encoding used for both query components and path segments. Unreserved
     * characters {@code A-Z a-z 0-9 - _ . ~} pass through; everything else (including {@code /},
     * space and the sub-delimiters) is percent-encoded with uppercase hex.
     */
    private static String rfc3986(String value) {
        StringBuilder sb = new StringBuilder(value.length() * 2);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static byte[] signingKey(String secretKey, String dateStamp, String region) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
                dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, SERVICE.getBytes(StandardCharsets.UTF_8));
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
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
