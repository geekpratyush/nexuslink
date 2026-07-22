package com.nexuslink.protocol.gcs;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Google Cloud Storage client. Authenticate with a service-account JSON key file (or fall back to
 * Application Default Credentials when no key is supplied); then browse buckets → objects.
 * <p>
 * When the {@code STORAGE_EMULATOR_HOST} environment variable is set (e.g. a local fake-gcs-server),
 * the client is pointed at that host with anonymous credentials so it can be exercised offline.
 */
public final class GcsService implements AutoCloseable {

    private Storage storage;

    /** A listed object's headline metadata. */
    public record GcsObject(String name, long size, String updated, String contentType, String storageClass) {}

    public void connect(String projectId, String credentialsJsonPath) throws Exception {
        close();
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        if (projectId != null && !projectId.isBlank()) builder.setProjectId(projectId);

        String emulatorHost = System.getenv("STORAGE_EMULATOR_HOST");
        if (emulatorHost != null && !emulatorHost.isBlank()) {
            // Local emulator: no real auth, talk to the emulator endpoint directly.
            builder.setHost(emulatorHost);
            builder.setCredentials(NoCredentials.getInstance());
        } else if (credentialsJsonPath != null && !credentialsJsonPath.isBlank()) {
            try (FileInputStream in = new FileInputStream(credentialsJsonPath)) {
                builder.setCredentials(GoogleCredentials.fromStream(in));
            }
        }
        storage = builder.build().getService();
        storage.list(Storage.BucketListOption.pageSize(1)); // force a round-trip to verify
    }

    public boolean isConnected() { return storage != null; }

    public List<String> listBuckets() {
        List<String> names = new ArrayList<>();
        for (Bucket b : storage.list().iterateAll()) names.add(b.getName());
        return names;
    }

    /** Lists up to {@code max} objects in a bucket. */
    public List<GcsObject> listObjects(String bucket, int max) {
        List<GcsObject> objects = new ArrayList<>();
        for (Blob b : storage.list(bucket, Storage.BlobListOption.pageSize(Math.min(max, 1000))).iterateAll()) {
            objects.add(toObject(b));
            if (objects.size() >= max) break;
        }
        return objects;
    }

    /** A single directory-level listing under a prefix: sub-"folders" (virtual dirs) and objects. */
    public record GcsListing(List<String> folders, List<GcsObject> files) {}

    /**
     * Lists one directory level of {@code bucket} under {@code prefix} using GCS's "current directory"
     * mode, so virtual directories come back as sub-folders and only the immediate objects as files. The
     * folder-marker object equal to the prefix itself is skipped. This is what the commander navigates.
     */
    public GcsListing listChildren(String bucket, String prefix) {
        String p = prefix == null ? "" : prefix;
        List<String> folders = new ArrayList<>();
        List<GcsObject> files = new ArrayList<>();
        for (Blob b : storage.list(bucket,
                Storage.BlobListOption.prefix(p),
                Storage.BlobListOption.currentDirectory()).iterateAll()) {
            if (b.isDirectory()) {
                folders.add(b.getName());        // already trailing-slashed
            } else if (!b.getName().equals(p)) {
                files.add(toObject(b));
            }
        }
        return new GcsListing(folders, files);
    }

    /** Lists up to {@code max} objects under {@code prefix}, flat (recursive) — used for recursive delete. */
    public List<GcsObject> listObjectsUnder(String bucket, String prefix, int max) {
        List<GcsObject> objects = new ArrayList<>();
        for (Blob b : storage.list(bucket, Storage.BlobListOption.prefix(prefix == null ? "" : prefix))
                .iterateAll()) {
            objects.add(toObject(b));
            if (objects.size() >= max) break;
        }
        return objects;
    }

    /** Fetches up to {@code maxBytes} of an object as raw bytes (quick-view / edit-in-place). */
    public byte[] getObjectBytes(String bucket, String object, int maxBytes) {
        byte[] bytes = storage.readAllBytes(BlobId.of(bucket, object));
        if (bytes.length <= maxBytes) return bytes;
        byte[] trimmed = new byte[maxBytes];
        System.arraycopy(bytes, 0, trimmed, 0, maxBytes);
        return trimmed;
    }

    /** Downloads an object to a local file, reporting the byte count once on completion. */
    public void downloadToFile(String bucket, String object, Path localTarget, LongConsumer progress)
            throws Exception {
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        Blob blob = storage.get(BlobId.of(bucket, object));
        if (blob == null) throw new java.io.FileNotFoundException("gs://" + bucket + "/" + object);
        blob.downloadTo(localTarget);
        if (progress != null && Files.exists(localTarget)) progress.accept(Files.size(localTarget));
    }

    /** Uploads a local file to {@code bucket}/{@code object}, reporting the byte count once on completion. */
    public void uploadFile(String bucket, String object, Path localSource, LongConsumer progress)
            throws Exception {
        storage.createFrom(com.google.cloud.storage.BlobInfo.newBuilder(BlobId.of(bucket, object)).build(),
                localSource);
        if (progress != null) progress.accept(Files.size(localSource));
    }

    /** Creates a zero-byte "folder marker" object ({@code prefix/}) so an empty folder is browsable. */
    public void createFolder(String bucket, String prefix) {
        String name = prefix.endsWith("/") ? prefix : prefix + "/";
        putObject(bucket, name, new byte[0], null);
    }

    /** Uploads {@code bytes} to {@code bucket}/{@code object}; {@code contentType} may be null. */
    public void putObject(String bucket, String object, byte[] bytes, String contentType) {
        var builder = com.google.cloud.storage.BlobInfo.newBuilder(BlobId.of(bucket, object));
        if (contentType != null && !contentType.isBlank()) builder.setContentType(contentType);
        storage.create(builder.build(), bytes);
    }

    /** Uploads UTF-8 {@code text} to {@code bucket}/{@code object} as {@code text/plain}. */
    public void putText(String bucket, String object, String text) {
        putObject(bucket, object, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    /** Deletes a single object; a missing object is not an error. */
    public void deleteObject(String bucket, String object) {
        storage.delete(BlobId.of(bucket, object));
    }

    private static GcsObject toObject(Blob b) {
        return new GcsObject(
                b.getName(),
                b.getSize() == null ? 0 : b.getSize(),
                b.getUpdateTimeOffsetDateTime() == null ? "" : b.getUpdateTimeOffsetDateTime().toString(),
                b.getContentType() == null ? "" : b.getContentType(),
                b.getStorageClass() == null ? "" : b.getStorageClass().toString());
    }

    @Override
    public void close() {
        if (storage != null) { try { storage.close(); } catch (Exception ignored) { } storage = null; }
    }
}
