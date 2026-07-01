package com.nexuslink.protocol.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Azure Blob tests against the local {@code test-env} Azurite emulator (well-known dev account).
 * A container + blob are seeded via the Azure SDK, then listed back through {@link AzureBlobService}.
 * <pre>docker compose -f test-env/docker-compose.yml up -d azurite</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AzureLiveIT {

    // Azurite's fixed development account + key.
    private static final String CONN =
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;";
    private static final String CONTAINER = "nexus-it";
    private static final String BLOB = "hello.txt";

    @BeforeAll
    void seed() {
        BlobServiceClient svc = new BlobServiceClientBuilder().connectionString(CONN).buildClient();
        BlobContainerClient container = svc.getBlobContainerClient(CONTAINER);
        if (!container.exists()) container.create();
        byte[] data = "hello-azure".getBytes(StandardCharsets.UTF_8);
        container.getBlobClient(BLOB).upload(new ByteArrayInputStream(data), data.length, true);
    }

    @Test
    void listContainersAndBlobs() {
        try (AzureBlobService svc = new AzureBlobService()) {
            svc.connect(CONN);
            assertTrue(svc.isConnected());
            assertTrue(svc.listContainers().contains(CONTAINER));

            List<AzureBlobService.BlobInfo> blobs = svc.listBlobs(CONTAINER, 100);
            assertTrue(blobs.stream().anyMatch(b -> b.name().equals(BLOB)));
        }
    }
}
