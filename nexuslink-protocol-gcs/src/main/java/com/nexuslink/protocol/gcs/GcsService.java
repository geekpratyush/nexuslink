package com.nexuslink.protocol.gcs;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

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
            objects.add(new GcsObject(
                    b.getName(),
                    b.getSize() == null ? 0 : b.getSize(),
                    b.getUpdateTimeOffsetDateTime() == null ? "" : b.getUpdateTimeOffsetDateTime().toString(),
                    b.getContentType() == null ? "" : b.getContentType(),
                    b.getStorageClass() == null ? "" : b.getStorageClass().toString()));
            if (objects.size() >= max) break;
        }
        return objects;
    }

    @Override
    public void close() {
        if (storage != null) { try { storage.close(); } catch (Exception ignored) { } storage = null; }
    }
}
