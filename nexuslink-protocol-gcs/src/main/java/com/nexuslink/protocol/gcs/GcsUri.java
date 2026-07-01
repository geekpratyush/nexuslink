package com.nexuslink.protocol.gcs;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Parses the ways a Google Cloud Storage object location is written into {@code {bucket, object}}.
 * Pure string parsing (no Google client), so it is offline-testable. Recognizes:
 * <ul>
 *   <li>the {@code gs://bucket/object} scheme (object optional),</li>
 *   <li>path-style HTTPS: {@code https://storage.googleapis.com/bucket/object} and the console host
 *       {@code https://storage.cloud.google.com/bucket/object},</li>
 *   <li>virtual-hosted HTTPS: {@code https://bucket.storage.googleapis.com/object}.</li>
 * </ul>
 * The object component is URL-decoded; {@link #toGsUri()} renders the canonical {@code gs://} form.
 */
public record GcsUri(String bucket, String object) {

    private static final String API_HOST = "storage.googleapis.com";
    private static final String CONSOLE_HOST = "storage.cloud.google.com";

    /** Thrown when a string cannot be parsed as a GCS location. */
    public static final class GcsUriException extends IllegalArgumentException {
        public GcsUriException(String message) { super(message); }
    }

    /** Parses {@code input} (a {@code gs://} URI or a googleapis HTTPS URL) into bucket + object. */
    public static GcsUri parse(String input) {
        if (input == null || input.isBlank()) throw new GcsUriException("Empty GCS URI");
        String s = input.trim();

        if (s.startsWith("gs://")) {
            String rest = s.substring("gs://".length());
            int slash = rest.indexOf('/');
            String bucket = slash < 0 ? rest : rest.substring(0, slash);
            String object = slash < 0 ? "" : rest.substring(slash + 1);
            return build(bucket, object);
        }

        if (s.startsWith("http://") || s.startsWith("https://")) {
            String afterScheme = s.substring(s.indexOf("://") + 3);
            int slash = afterScheme.indexOf('/');
            String host = slash < 0 ? afterScheme : afterScheme.substring(0, slash);
            String path = slash < 0 ? "" : afterScheme.substring(slash + 1);
            host = host.toLowerCase();

            if (host.equals(API_HOST) || host.equals(CONSOLE_HOST)) {
                // Path-style: /bucket/object...
                int p = path.indexOf('/');
                String bucket = p < 0 ? path : path.substring(0, p);
                String object = p < 0 ? "" : path.substring(p + 1);
                return build(bucket, object);
            }
            if (host.endsWith("." + API_HOST)) {
                // Virtual-hosted: bucket.storage.googleapis.com/object
                String bucket = host.substring(0, host.length() - ("." + API_HOST).length());
                return build(bucket, path);
            }
            throw new GcsUriException("Not a Google Cloud Storage host: " + host);
        }

        throw new GcsUriException("Unrecognized GCS URI: " + input);
    }

    private static GcsUri build(String bucket, String rawObject) {
        if (bucket == null || bucket.isBlank()) throw new GcsUriException("Missing bucket");
        String object = rawObject == null ? "" : URLDecoder.decode(rawObject, StandardCharsets.UTF_8);
        return new GcsUri(bucket, object);
    }

    /** Whether this location points at a specific object (vs. a bucket only). */
    public boolean hasObject() { return object != null && !object.isEmpty(); }

    /** The canonical {@code gs://bucket[/object]} form. */
    public String toGsUri() {
        return hasObject() ? "gs://" + bucket + "/" + object : "gs://" + bucket;
    }

    @Override
    public String toString() { return toGsUri(); }
}
