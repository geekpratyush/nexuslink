package com.nexuslink.protocol.gcs;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live GCS tests against the local {@code test-env} fake-gcs-server.
 * <p>
 * The Google client talks to the emulator via the {@code STORAGE_EMULATOR_HOST} env var, so run:
 * <pre>docker compose -f test-env/docker-compose.yml up -d fake-gcs
 * STORAGE_EMULATOR_HOST=http://localhost:4443 mvn -pl nexuslink-protocol-gcs test -Dnexuslink.it=true</pre>
 * Gated on BOTH {@code -Dnexuslink.it=true} and the env var so it only runs when pointed at an emulator.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@EnabledIfEnvironmentVariable(named = "STORAGE_EMULATOR_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GcsLiveIT {

    private static final String PROJECT = "test-project";
    private static final String BUCKET = "nexus-it";
    private static final String OBJECT = "hello.txt";

    @BeforeAll
    void seed() throws Exception {
        // fake-gcs-server doesn't accept the Google client's create/upload paths, so seed the bucket
        // and object directly via its plain JSON + media-upload REST API. GcsService (list-only) is
        // still the code under test below.
        String host = System.getenv("STORAGE_EMULATOR_HOST");
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> bucket = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(host + "/storage/v1/b?project=" + PROJECT))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + BUCKET + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(bucket.statusCode() < 500, "bucket seed failed: " + bucket.statusCode() + " " + bucket.body());

        HttpResponse<String> obj = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(host + "/upload/storage/v1/b/" + BUCKET
                                + "/o?uploadType=media&name=" + OBJECT))
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString("hello-gcs", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(obj.statusCode() < 300, "object seed failed: " + obj.statusCode() + " " + obj.body());
    }

    @Test
    void listBucketsAndObjects() throws Exception {
        try (GcsService svc = new GcsService()) {
            svc.connect(PROJECT, null);
            assertTrue(svc.isConnected());
            assertTrue(svc.listBuckets().contains(BUCKET));

            List<GcsService.GcsObject> objs = svc.listObjects(BUCKET, 100);
            assertTrue(objs.stream().anyMatch(o -> o.name().equals(OBJECT)));
        }
    }
}
