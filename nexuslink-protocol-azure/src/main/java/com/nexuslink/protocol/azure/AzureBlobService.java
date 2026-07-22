package com.nexuslink.protocol.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.common.StorageSharedKeyCredential;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Azure Blob Storage client. Connect with a connection string (the common case, incl. the local
 * Azurite emulator) or an account endpoint + shared key; then browse containers → blobs.
 */
public final class AzureBlobService implements AutoCloseable {

    private BlobServiceClient client;

    /** A listed blob's headline metadata. */
    public record BlobInfo(String name, long size, String lastModified, String contentType, String tier) {}

    public void connect(String connectionString) {
        close();
        client = new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
        client.getAccountInfo(); // force a round-trip to verify
    }

    public void connectWithKey(String endpoint, String account, String key) {
        close();
        client = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new StorageSharedKeyCredential(account, key))
                .buildClient();
        client.getAccountInfo();
    }

    public boolean isConnected() { return client != null; }

    public List<String> listContainers() {
        List<String> names = new ArrayList<>();
        client.listBlobContainers().forEach(c -> names.add(c.getName()));
        return names;
    }

    /** Lists up to {@code max} blobs in a container. */
    public List<BlobInfo> listBlobs(String container, int max) {
        List<BlobInfo> blobs = new ArrayList<>();
        var containerClient = client.getBlobContainerClient(container);
        for (BlobItem item : containerClient.listBlobs()) {
            blobs.add(toInfo(item));
            if (blobs.size() >= max) break;
        }
        return blobs;
    }

    /** A single directory-level listing under a prefix: sub-"folders" (virtual dirs) and blobs. */
    public record BlobListing(List<String> folders, List<BlobInfo> files) {}

    /**
     * Lists one directory level of {@code container} under {@code prefix} using the {@code /} delimiter, so
     * virtual directories come back as sub-folders and only the immediate blobs as files. The folder-marker
     * blob equal to the prefix itself is skipped. This is what the object-storage commander navigates.
     */
    public BlobListing listChildren(String container, String prefix) {
        String p = prefix == null ? "" : prefix;
        List<String> folders = new ArrayList<>();
        List<BlobInfo> files = new ArrayList<>();
        var containerClient = client.getBlobContainerClient(container);
        var options = new ListBlobsOptions().setPrefix(p);
        for (BlobItem item : containerClient.listBlobsByHierarchy("/", options, null)) {
            if (Boolean.TRUE.equals(item.isPrefix())) {
                folders.add(item.getName());     // already trailing-slashed by the service
            } else if (!item.getName().equals(p)) {
                files.add(toInfo(item));
            }
        }
        return new BlobListing(folders, files);
    }

    /** Lists up to {@code max} blobs under {@code prefix}, flat (no delimiter) — used for recursive delete. */
    public List<BlobInfo> listBlobsUnder(String container, String prefix, int max) {
        List<BlobInfo> blobs = new ArrayList<>();
        var containerClient = client.getBlobContainerClient(container);
        var options = new ListBlobsOptions().setPrefix(prefix == null ? "" : prefix);
        for (BlobItem item : containerClient.listBlobs(options, null)) {
            blobs.add(toInfo(item));
            if (blobs.size() >= max) break;
        }
        return blobs;
    }

    /** Fetches up to {@code maxBytes} of a blob as raw bytes (quick-view / edit-in-place). */
    public byte[] getBlobBytes(String container, String blob, int maxBytes) {
        byte[] bytes = client.getBlobContainerClient(container).getBlobClient(blob)
                .downloadContent().toBytes();
        if (bytes.length <= maxBytes) return bytes;
        byte[] trimmed = new byte[maxBytes];
        System.arraycopy(bytes, 0, trimmed, 0, maxBytes);
        return trimmed;
    }

    /** Downloads a blob to a local file, reporting the byte count once on completion. */
    public void downloadToFile(String container, String blob, Path localTarget, LongConsumer progress)
            throws Exception {
        if (localTarget.getParent() != null) Files.createDirectories(localTarget.getParent());
        client.getBlobContainerClient(container).getBlobClient(blob)
                .downloadToFile(localTarget.toString(), true);
        if (progress != null && Files.exists(localTarget)) progress.accept(Files.size(localTarget));
    }

    /** Uploads a local file to {@code container}/{@code blob}, reporting the byte count once on completion. */
    public void uploadFile(String container, String blob, Path localSource, LongConsumer progress)
            throws Exception {
        client.getBlobContainerClient(container).getBlobClient(blob)
                .uploadFromFile(localSource.toString(), true);
        if (progress != null) progress.accept(Files.size(localSource));
    }

    /** Creates a zero-byte "folder marker" blob ({@code prefix/}) so an empty folder is browsable. */
    public void createFolder(String container, String prefix) {
        String name = prefix.endsWith("/") ? prefix : prefix + "/";
        putBlob(container, name, new byte[0], null);
    }

    /** Uploads {@code bytes} to {@code container}/{@code blob}; {@code contentType} may be null. */
    public void putBlob(String container, String blob, byte[] bytes, String contentType) {
        var blobClient = client.getBlobContainerClient(container).getBlobClient(blob);
        blobClient.upload(BinaryData.fromBytes(bytes), true);
        if (contentType != null && !contentType.isBlank()) {
            blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
        }
    }

    /** Uploads UTF-8 {@code text} to {@code container}/{@code blob} as {@code text/plain}. */
    public void putText(String container, String blob, String text) {
        putBlob(container, blob, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    /** Deletes a single blob; a missing blob is not an error. */
    public void deleteBlob(String container, String blob) {
        client.getBlobContainerClient(container).getBlobClient(blob).deleteIfExists();
    }

    private static BlobInfo toInfo(BlobItem item) {
        var p = item.getProperties();
        return new BlobInfo(
                item.getName(),
                p == null || p.getContentLength() == null ? 0 : p.getContentLength(),
                p == null || p.getLastModified() == null ? "" : p.getLastModified().toString(),
                p == null ? "" : p.getContentType(),
                p == null || p.getAccessTier() == null ? "" : p.getAccessTier().toString());
    }

    @Override
    public void close() { client = null; }
}
