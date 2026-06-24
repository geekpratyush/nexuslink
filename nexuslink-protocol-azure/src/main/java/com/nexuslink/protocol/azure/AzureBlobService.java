package com.nexuslink.protocol.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.common.StorageSharedKeyCredential;

import java.util.ArrayList;
import java.util.List;

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
            var p = item.getProperties();
            blobs.add(new BlobInfo(
                    item.getName(),
                    p == null || p.getContentLength() == null ? 0 : p.getContentLength(),
                    p == null || p.getLastModified() == null ? "" : p.getLastModified().toString(),
                    p == null ? "" : p.getContentType(),
                    p == null || p.getAccessTier() == null ? "" : p.getAccessTier().toString()));
            if (blobs.size() >= max) break;
        }
        return blobs;
    }

    @Override
    public void close() { client = null; }
}
