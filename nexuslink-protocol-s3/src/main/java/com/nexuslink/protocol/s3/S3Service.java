package com.nexuslink.protocol.s3;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
    private long multipartThreshold = MultipartPlan.DEFAULT_THRESHOLD;
    private long partSize = MultipartPlan.DEFAULT_PART_SIZE;

    /** A listed object's headline metadata. */
    public record S3Item(String key, long size, String lastModified, String etag, String storageClass) {}

    /**
     * Sets the size at which {@link #uploadFile} switches to a multipart upload and the size it aims
     * for per part. Both are advisory — {@link MultipartPlan} clamps the part size into S3's legal
     * range and grows it if the file would otherwise need more than 10,000 parts.
     */
    public void setMultipartUpload(long threshold, long preferredPartSize) {
        this.multipartThreshold = threshold;
        this.partSize = preferredPartSize;
    }

    public long multipartThreshold() { return multipartThreshold; }

    public long partSize() { return partSize; }

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

    /**
     * Uploads a local file to {@code bucket}/{@code key}. Files at or above the multipart threshold go
     * up part by part (see {@link MultipartPlan}), so {@code progress} — cumulative bytes sent — ticks
     * as each part lands instead of jumping to 100% at the end; smaller files are a single PutObject.
     */
    public void uploadFile(String bucket, String key, Path localSource, LongConsumer progress) throws Exception {
        long size = Files.size(localSource);
        MultipartPlan plan = MultipartPlan.of(size, multipartThreshold, partSize);
        if (plan.multipart()) {
            uploadMultipart(bucket, key, localSource, plan, progress);
        } else {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromFile(localSource));
            if (progress != null) progress.accept(size);
        }
    }

    /**
     * Sends {@code localSource} as an S3 multipart upload. Each part is streamed straight off the file
     * at its own offset (nothing larger than a buffer is held in memory), and a failure aborts the
     * upload so the parts already stored don't linger and accrue storage charges.
     */
    private void uploadMultipart(String bucket, String key, Path localSource, MultipartPlan plan,
                                 LongConsumer progress) throws Exception {
        String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(bucket).key(key).build()).uploadId();
        try {
            List<CompletedPart> completed = new ArrayList<>();
            long sent = 0;
            for (int part = 1; part <= plan.partCount(); part++) {
                long offset = plan.offsetOf(part);
                long length = plan.sizeOf(part);
                final int partNumber = part;
                String etag = client.uploadPart(UploadPartRequest.builder()
                                .bucket(bucket).key(key).uploadId(uploadId)
                                .partNumber(partNumber).contentLength(length).build(),
                        // A provider rather than a fixed stream: the SDK reopens it at the right
                        // offset if it has to retry the part.
                        RequestBody.fromContentProvider(
                                () -> openRange(localSource, offset, length), length,
                                "application/octet-stream")).eTag();
                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                sent += length;
                if (progress != null) progress.accept(sent);
            }
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
        } catch (RuntimeException | Error e) {
            abortQuietly(bucket, key, uploadId);
            throw e;
        }
    }

    /** Opens {@code length} bytes of {@code file} starting at {@code offset} as a fresh stream. */
    private static InputStream openRange(Path file, long offset, long length) {
        try {
            InputStream in = Files.newInputStream(file);
            try {
                in.skipNBytes(offset);
            } catch (IOException | RuntimeException e) {
                in.close();
                throw e;
            }
            return new BoundedInputStream(in, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Best-effort abort — the original upload failure is what the caller needs to see. */
    private void abortQuietly(String bucket, String key, String uploadId) {
        try {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (RuntimeException ignored) {
            // nothing useful to do; S3 lifecycle rules clean up stale parts
        }
    }

    /** Caps a stream at {@code limit} bytes so a part never bleeds into the next one. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) { super(in); this.remaining = limit; }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
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
