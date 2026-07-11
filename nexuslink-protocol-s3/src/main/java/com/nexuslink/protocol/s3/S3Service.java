package com.nexuslink.protocol.s3;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * S3 / S3-compatible object-storage client (AWS, MinIO, Wasabi, …) over the AWS SDK v2 with the
 * lightweight URL-connection HTTP client. Connect with an endpoint + access/secret key; path-style
 * access is used so it works against MinIO and other non-AWS endpoints.
 */
public final class S3Service implements AutoCloseable {

    private S3Client client;

    /** A listed object's headline metadata. */
    public record S3Item(String key, long size, String lastModified, String etag, String storageClass) {}

    public void connect(String endpoint, String accessKey, String secretKey, String region, boolean pathStyle) {
        close();
        var b = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .httpClient(UrlConnectionHttpClient.create());
        if (endpoint != null && !endpoint.isBlank()) b.endpointOverride(URI.create(endpoint));
        this.client = b.build();
    }

    public boolean isConnected() { return client != null; }

    public List<String> listBuckets() {
        List<String> names = new ArrayList<>();
        client.listBuckets().buckets().forEach(bk -> names.add(bk.name()));
        return names;
    }

    /** Lists up to {@code maxKeys} objects in a bucket under an optional key prefix. */
    public List<S3Item> listObjects(String bucket, String prefix, int maxKeys) {
        ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix == null ? "" : prefix)
                .maxKeys(maxKeys)
                .build();
        ListObjectsV2Response resp = client.listObjectsV2(req);
        List<S3Item> items = new ArrayList<>();
        resp.contents().forEach(o -> items.add(new S3Item(
                o.key(), o.size() == null ? 0 : o.size(),
                o.lastModified() == null ? "" : o.lastModified().toString(),
                o.eTag(), o.storageClassAsString())));
        return items;
    }

    /** A single directory-level listing under a prefix: sub-"folders" (common prefixes) and objects. */
    public record S3Listing(List<String> folders, List<S3Item> files) {}

    /**
     * Lists one directory level of {@code bucket} under {@code prefix} using the {@code /} delimiter, so
     * common prefixes come back as sub-folders and only the immediate objects as files. The folder-marker
     * object equal to the prefix itself is skipped. This is what the object-storage commander navigates.
     */
    public S3Listing listChildren(String bucket, String prefix) {
        String p = prefix == null ? "" : prefix;
        ListObjectsV2Response resp = client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket).prefix(p).delimiter("/").maxKeys(1000).build());
        List<String> folders = new ArrayList<>();
        resp.commonPrefixes().forEach(cp -> folders.add(cp.prefix()));
        List<S3Item> files = new ArrayList<>();
        resp.contents().forEach(o -> {
            if (o.key().equals(p)) return; // the zero-byte folder marker for this prefix — not a file
            files.add(new S3Item(o.key(), o.size() == null ? 0 : o.size(),
                    o.lastModified() == null ? "" : o.lastModified().toString(),
                    o.eTag(), o.storageClassAsString()));
        });
        return new S3Listing(folders, files);
    }

    /** Fetches up to {@code maxBytes} of an object as raw bytes (quick-view / edit-in-place). */
    public byte[] getObjectBytes(String bucket, String key, int maxBytes) {
        byte[] bytes = client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(key).build()).asByteArray();
        if (bytes.length <= maxBytes) return bytes;
        byte[] trimmed = new byte[maxBytes];
        System.arraycopy(bytes, 0, trimmed, 0, maxBytes);
        return trimmed;
    }

    /** Downloads an object to a local file, reporting the byte count once on completion. */
    public void downloadToFile(String bucket, String key, Path localTarget, LongConsumer progress) throws Exception {
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), localTarget);
        if (progress != null && Files.exists(localTarget)) progress.accept(Files.size(localTarget));
    }

    /** Uploads a local file to {@code bucket}/{@code key}, reporting the byte count once on completion. */
    public void uploadFile(String bucket, String key, Path localSource, LongConsumer progress) throws Exception {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromFile(localSource));
        if (progress != null) progress.accept(Files.size(localSource));
    }

    /** Creates a zero-byte "folder marker" object ({@code prefix/}) so an empty folder is browsable. */
    public void createFolder(String bucket, String prefix) {
        String key = prefix.endsWith("/") ? prefix : prefix + "/";
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.empty());
    }

    /** Uploads {@code bytes} to {@code bucket}/{@code key}; {@code contentType} may be null. */
    public void putObject(String bucket, String key, byte[] bytes, String contentType) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key);
        if (contentType != null && !contentType.isBlank()) req.contentType(contentType);
        client.putObject(req.build(), RequestBody.fromBytes(bytes));
    }

    /** Uploads UTF-8 {@code text} to {@code bucket}/{@code key} as {@code text/plain}. */
    public void putText(String bucket, String key, String text) {
        putObject(bucket, key, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    /** Deletes a single object. */
    public void deleteObject(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /** Fetches the first {@code maxBytes} of an object as UTF-8 text (for previews). */
    public String getObjectAsText(String bucket, String key, int maxBytes) {
        byte[] bytes = client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(key).build()).asByteArray();
        int len = Math.min(bytes.length, maxBytes);
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (client != null) { client.close(); client = null; }
    }
}
