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
import java.util.ArrayList;
import java.util.List;

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
