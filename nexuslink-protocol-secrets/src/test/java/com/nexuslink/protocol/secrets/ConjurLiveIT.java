package com.nexuslink.protocol.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live CyberArk Conjur OSS round-trip. Conjur needs one-off {@code conjurctl} provisioning that a
 * healthcheck can't do, so bring it up + provision first, then pass the admin API key in:
 * <pre>
 * docker compose -f test-env/docker-compose.yml --profile conjur up -d
 * test-env/conjur/setup.sh
 * mvn -pl nexuslink-protocol-secrets test -Dnexuslink.it=true -Dtest=ConjurLiveIT \
 *     -Dconjur.account=myConjurAccount -Dconjur.apiKey="$(cat test-env/conjur/.admin-api-key)"
 * </pre>
 * Gated on {@code -Dnexuslink.it=true}; also skips (assumption) if the admin API key isn't supplied.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class ConjurLiveIT {

    private static final String URL = System.getProperty("conjur.url", "http://localhost:8083");
    private static final String ACCOUNT = System.getProperty("conjur.account", "myConjurAccount");
    private static final String API_KEY = System.getProperty("conjur.apiKey", "");

    @Test
    void authenticateThenReadSecret() {
        assumeTrue(!API_KEY.isBlank(),
                "set -Dconjur.apiKey=<admin key from test-env/conjur/setup.sh> to run this");
        try (ConjurService c = new ConjurService()) {
            assertTrue(c.ping(URL), "Conjur /info should be reachable");

            String token = c.authenticate(URL, ACCOUNT, "admin", API_KEY);
            assertNotNull(token);
            assertTrue(c.isConnected());

            assertEquals("s3cr3t-value", c.getSecret("nexus/db/password"),
                    "value set by test-env/conjur/setup.sh");
        }
    }
}
