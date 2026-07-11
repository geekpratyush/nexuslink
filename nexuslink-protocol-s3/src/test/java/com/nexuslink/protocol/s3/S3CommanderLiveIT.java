package com.nexuslink.protocol.s3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live test of the object-storage commander's data plane — the S3 {@link S3Service} methods that back
 * {@code S3FileSystem} (delimiter listing, folder markers, file upload/download, byte read) — against
 * the local {@code test-env} LocalStack S3 endpoint.
 * <pre>docker compose -f test-env/docker-compose.yml up -d localstack</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3CommanderLiveIT {

    private static final String ENDPOINT = "http://localhost:4566";
    private static final String BUCKET = "nexus-commander-it";

    @TempDir Path tmp;

    @BeforeAll
    void seedBucket() {
        try (S3Client c = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(ENDPOINT))
                .build()) {
            try { c.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()); }
            catch (Exception alreadyExists) { /* idempotent */ }
        }
    }

    @Test
    void folderUploadListDownloadRoundTrip() throws Exception {
        try (S3Service svc = new S3Service()) {
            svc.connect(ENDPOINT, "test", "test", "us-east-1", true);
            assertTrue(svc.isConnected());

            // Upload a file at the bucket root.
            Path local = tmp.resolve("root.txt");
            Files.writeString(local, "root-content");
            svc.uploadFile(BUCKET, "root.txt", local, b -> {});

            // Create a sub-folder marker and upload a file inside it.
            svc.createFolder(BUCKET, "docs/");
            Path inner = tmp.resolve("inner.txt");
            Files.writeString(inner, "inner-content");
            svc.uploadFile(BUCKET, "docs/inner.txt", inner, b -> {});

            // Root listing: the file "root.txt" is a file, "docs/" is a common-prefix folder.
            S3Service.S3Listing root = svc.listChildren(BUCKET, "");
            assertTrue(root.folders().contains("docs/"), "docs/ should list as a sub-folder");
            assertTrue(root.files().stream().anyMatch(f -> f.key().equals("root.txt")),
                    "root.txt should list as a file");
            assertTrue(root.files().stream().noneMatch(f -> f.key().equals("docs/")),
                    "the folder marker must not appear as a file");

            // Folder listing: only the inner file (the marker equal to the prefix is skipped).
            S3Service.S3Listing docs = svc.listChildren(BUCKET, "docs/");
            assertTrue(docs.files().stream().anyMatch(f -> f.key().equals("docs/inner.txt")));

            // Read bytes + download to a local file.
            assertEquals("inner-content",
                    new String(svc.getObjectBytes(BUCKET, "docs/inner.txt", 4096), StandardCharsets.UTF_8));
            Path out = tmp.resolve("downloaded.txt");
            svc.downloadToFile(BUCKET, "docs/inner.txt", out, b -> {});
            assertEquals("inner-content", Files.readString(out));

            // Clean up.
            svc.deleteObject(BUCKET, "docs/inner.txt");
            svc.deleteObject(BUCKET, "docs/");
            svc.deleteObject(BUCKET, "root.txt");
        }
    }
}
