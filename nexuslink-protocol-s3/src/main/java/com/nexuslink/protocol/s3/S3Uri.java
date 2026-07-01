package com.nexuslink.protocol.s3;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Pure, offline S3 object-location parser (no AWS SDK). Understands the three common ways an S3
 * object is addressed and normalises them into {@code {bucket, key, region, endpoint, style}}:
 *
 * <ul>
 *   <li>the AWS CLI scheme {@code s3://bucket/key/with/slashes} (key optional);</li>
 *   <li>virtual-hosted-style URLs such as {@code https://bucket.s3.us-east-1.amazonaws.com/key}
 *       or {@code https://bucket.s3-eu-west-1.amazonaws.com/key};</li>
 *   <li>path-style URLs such as {@code https://s3.us-west-2.amazonaws.com/bucket/key} and generic
 *       custom endpoints like {@code http://localhost:4566/bucket/key} (LocalStack / MinIO).</li>
 * </ul>
 *
 * The parsed key is URL-decoded; {@link #toS3Uri()} renders the canonical {@code s3://bucket/key}.
 */
public final class S3Uri {

    /** How the object location was written. */
    public enum Style { S3_SCHEME, VIRTUAL_HOSTED, PATH }

    /** Thrown when the input is not a recognisable S3 location. */
    public static final class S3UriException extends IllegalArgumentException {
        public S3UriException(String message) { super(message); }
    }

    private static final String AWS_SUFFIX = ".amazonaws.com";

    private final String bucket;
    private final String key;
    private final String region;
    private final String endpoint;
    private final Style style;

    private S3Uri(String bucket, String key, String region, String endpoint, Style style) {
        this.bucket = bucket;
        this.key = key;
        this.region = region;
        this.endpoint = endpoint;
        this.style = style;
    }

    /** The bucket name (never {@code null} or blank). */
    public String bucket() { return bucket; }

    /** The object key, URL-decoded; empty string when the location names only a bucket. */
    public String key() { return key; }

    /** The AWS region if it could be derived from the host, otherwise {@code null}. */
    public String region() { return region; }

    /** The {@code host[:port]} for path-style / custom endpoints, otherwise {@code null}. */
    public String endpoint() { return endpoint; }

    /** How the location was addressed. */
    public Style style() { return style; }

    /** Whether the location names an object (a non-empty key) rather than just a bucket. */
    public boolean hasKey() { return !key.isEmpty(); }

    /**
     * Parses an S3 location written as an {@code s3://} URI, a virtual-hosted-style URL, or a
     * path-style URL.
     *
     * @throws S3UriException if {@code input} is blank, has no bucket, or cannot be parsed
     */
    public static S3Uri parse(String input) {
        if (input == null || input.isBlank()) {
            throw new S3UriException("S3 location is null or blank");
        }
        String s = input.trim();
        String lower = s.toLowerCase();
        if (lower.startsWith("s3://")) {
            return parseS3Scheme(s);
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return parseUrl(s);
        }
        throw new S3UriException("Unrecognised S3 location: " + input);
    }

    private static S3Uri parseS3Scheme(String s) {
        String rest = s.substring("s3://".length());
        int slash = rest.indexOf('/');
        String bucket = slash < 0 ? rest : rest.substring(0, slash);
        String rawKey = slash < 0 ? "" : rest.substring(slash + 1);
        if (bucket.isEmpty()) {
            throw new S3UriException("s3:// location has no bucket: " + s);
        }
        return new S3Uri(bucket, decode(rawKey), null, null, Style.S3_SCHEME);
    }

    private static S3Uri parseUrl(String s) {
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new S3UriException("Malformed URL: " + s);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new S3UriException("URL has no host: " + s);
        }
        int port = uri.getPort();
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;

        String hostLower = host.toLowerCase();
        if (hostLower.endsWith(AWS_SUFFIX)) {
            String firstLabel = hostLower.contains(".")
                    ? hostLower.substring(0, hostLower.indexOf('.'))
                    : hostLower;
            boolean pathStyle = firstLabel.equals("s3") || firstLabel.startsWith("s3-");
            if (!pathStyle) {
                return virtualHosted(host, path, s);
            }
            String region = awsRegionFromServiceHost(hostLower);
            return pathStyle(host, port, path, region, s);
        }
        // Generic custom endpoint (LocalStack, MinIO, …) is always path-style.
        return pathStyle(host, port, path, null, s);
    }

    private static S3Uri virtualHosted(String host, String path, String s) {
        // host = bucket[.<more bucket labels>].s3[.<region>|-<region>].amazonaws.com
        String core = host.substring(0, host.length() - AWS_SUFFIX.length());
        String[] labels = core.split("\\.");
        int marker = -1;
        for (int i = 0; i < labels.length; i++) {
            String l = labels[i].toLowerCase();
            if (l.equals("s3") || l.startsWith("s3-")) { marker = i; break; }
        }
        if (marker <= 0) {
            throw new S3UriException("Cannot locate bucket in virtual-hosted host: " + host);
        }
        StringBuilder bucket = new StringBuilder();
        for (int i = 0; i < marker; i++) {
            if (i > 0) bucket.append('.');
            bucket.append(labels[i]);
        }
        String markerLabel = labels[marker].toLowerCase();
        String region;
        if (markerLabel.startsWith("s3-")) {
            region = markerLabel.substring(3);
        } else if (marker + 1 < labels.length) {
            region = labels[marker + 1];
        } else {
            region = null;
        }
        if (region != null && region.isEmpty()) region = null;
        return new S3Uri(bucket.toString(), decode(path), region, null, Style.VIRTUAL_HOSTED);
    }

    private static S3Uri pathStyle(String host, int port, String path, String region, String s) {
        int slash = path.indexOf('/');
        String bucket = slash < 0 ? path : path.substring(0, slash);
        String rawKey = slash < 0 ? "" : path.substring(slash + 1);
        if (bucket.isEmpty()) {
            throw new S3UriException("Path-style location has no bucket: " + s);
        }
        String endpoint = port > 0 ? host + ":" + port : host;
        return new S3Uri(bucket, decode(rawKey), region, endpoint, Style.PATH);
    }

    /** Extracts the region from a path-style AWS service host such as {@code s3.us-west-2.amazonaws.com}. */
    private static String awsRegionFromServiceHost(String hostLower) {
        String core = hostLower.substring(0, hostLower.length() - AWS_SUFFIX.length());
        if (core.equals("s3")) return null;
        if (core.startsWith("s3.")) return core.substring(3);
        if (core.startsWith("s3-")) return core.substring(3);
        return null;
    }

    /** Percent-decodes a key while preserving literal {@code '+'} (which is significant in S3 keys). */
    private static String decode(String raw) {
        if (raw.isEmpty() || raw.indexOf('%') < 0) return raw;
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Renders the canonical {@code s3://bucket/key} form (or {@code s3://bucket} when key-less). */
    public String toS3Uri() {
        return key.isEmpty() ? "s3://" + bucket : "s3://" + bucket + "/" + key;
    }

    @Override
    public String toString() {
        return "S3Uri{bucket=" + bucket + ", key=" + key + ", region=" + region
                + ", endpoint=" + endpoint + ", style=" + style + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof S3Uri other)) return false;
        return bucket.equals(other.bucket) && key.equals(other.key)
                && Objects.equals(region, other.region)
                && Objects.equals(endpoint, other.endpoint) && style == other.style;
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucket, key, region, endpoint, style);
    }
}
