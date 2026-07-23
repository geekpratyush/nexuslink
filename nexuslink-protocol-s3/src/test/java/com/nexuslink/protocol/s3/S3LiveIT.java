package com.nexuslink.protocol.s3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live S3 tests against the local {@code test-env} LocalStack S3 endpoint (path-style, dummy creds).
 * A bucket + object are seeded via the AWS SDK, then read back through {@link S3Service} (the code
 * under test, which is list/get only).
 * <pre>docker compose -f test-env/docker-compose.yml up -d localstack</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3LiveIT {

    private static final String ENDPOINT = "http://localhost:4566";
    private static final String BUCKET = "nexus-it";
    private static final String KEY = "hello.txt";
    private static final String BODY = "hello-s3-from-nexuslink";

    @BeforeAll
    void seed() {
        try (S3Client c = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(ENDPOINT))
                .build()) {
            try {
                c.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            } catch (Exception alreadyExists) { /* idempotent */ }
            c.putObject(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build(), RequestBody.fromString(BODY));
        }
    }

    @Test
    void listBucketsObjectsAndGet() {
        try (S3Service svc = new S3Service()) {
            svc.connect(ENDPOINT, "test", "test", "us-east-1", true);
            assertTrue(svc.isConnected());

            assertTrue(svc.listBuckets().contains(BUCKET));

            List<S3Service.S3Item> objs = svc.listObjects(BUCKET, "", 100);
            assertTrue(objs.stream().anyMatch(o -> o.key().equals(KEY)));

            assertEquals(BODY, svc.getObjectAsText(BUCKET, KEY, 4096));
        }
    }

    @Test
    void uploadReadThenDelete() {
        try (S3Service svc = new S3Service()) {
            svc.connect(ENDPOINT, "test", "test", "us-east-1", true);
            String key = "uploaded/it-" + System.currentTimeMillis() + ".txt";

            svc.putText(BUCKET, key, "written-by-nexuslink");
            assertTrue(svc.listObjects(BUCKET, "uploaded/", 100).stream()
                    .anyMatch(o -> o.key().equals(key)));
            assertEquals("written-by-nexuslink", svc.getObjectAsText(BUCKET, key, 4096));

            svc.deleteObject(BUCKET, key);
            assertTrue(svc.listObjects(BUCKET, "uploaded/", 100).stream()
                    .noneMatch(o -> o.key().equals(key)));
        }
    }

    /**
     * A real multipart upload: 12 MiB at S3's 5 MiB minimum part size is three parts (5 + 5 + 2), so
     * this exercises the create → uploadPart×N → complete path, the short final part, and progress
     * ticking per part rather than once at the end. The bytes are read back to prove the parts were
     * reassembled in order.
     */
    @Test
    void multipartUploadRoundTrip() throws Exception {
        Path local = Files.createTempFile("nexuslink-multipart", ".bin");
        try (S3Service svc = new S3Service()) {
            int size = 12 * 1024 * 1024;
            byte[] data = new byte[size];
            new Random(42).nextBytes(data);
            Files.write(local, data);

            svc.connect(ENDPOINT, "test", "test", "us-east-1", true);
            svc.setMultipartUpload(1024 * 1024, MultipartPlan.MIN_PART_SIZE);
            String key = "multipart/it-" + System.currentTimeMillis() + ".bin";

            List<Long> ticks = new ArrayList<>();
            svc.uploadFile(BUCKET, key, local, ticks::add);

            assertEquals(3, ticks.size(), "expected one progress tick per part");
            assertEquals(List.of(5L << 20, 10L << 20, (long) size), ticks);

            assertEquals(size, svc.listObjects(BUCKET, "multipart/", 100).stream()
                    .filter(o -> o.key().equals(key)).findFirst().orElseThrow().size());
            assertArrayEquals(data, svc.getObjectBytes(BUCKET, key, size));

            svc.deleteObject(BUCKET, key);
        } finally {
            Files.deleteIfExists(local);
        }
    }
}
