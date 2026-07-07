package com.nexuslink.protocol.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live AWS Secrets Manager round-trips against the local {@code test-env} LocalStack
 * (SERVICES includes {@code secretsmanager}).
 * <pre>docker compose -f test-env/docker-compose.yml up -d localstack</pre>
 * Run with {@code -Dnexuslink.it=true}. Endpoint: http://localhost:4566 (creds test/test).
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class SecretsManagerLiveIT {

    private static final String ENDPOINT = "http://localhost:4566";

    @Test
    void createReadUpdateListDeleteRoundTrip() {
        String name = "nexus-it-secret-" + System.currentTimeMillis();
        try (SecretsManagerService sm = new SecretsManagerService()) {
            sm.connect(ENDPOINT, "us-east-1", "test", "test");
            assertTrue(sm.isConnected());

            String arn = sm.createSecret(name, "{\"user\":\"nexus\",\"pass\":\"p1\"}");
            assertNotNull(arn);
            assertTrue(sm.listSecrets().stream().anyMatch(s -> s.name().equals(name)),
                    "created secret should appear in the list");

            assertEquals("{\"user\":\"nexus\",\"pass\":\"p1\"}", sm.getSecretValue(name));

            String v2 = sm.putSecretValue(name, "{\"user\":\"nexus\",\"pass\":\"p2\"}");
            assertNotNull(v2);
            assertEquals("{\"user\":\"nexus\",\"pass\":\"p2\"}", sm.getSecretValue(name),
                    "getSecretValue returns the current (newest) version");

            List<SecretsManagerService.SecretVersion> versions = sm.listVersions(name);
            assertTrue(versions.size() >= 2, "two puts should yield at least two versions: " + versions);

            sm.deleteSecret(name, true);
            assertTrue(sm.listSecrets().stream().noneMatch(s -> s.name().equals(name)),
                    "force-deleted secret should be gone");
        }
    }
}
