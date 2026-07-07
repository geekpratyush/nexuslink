package com.nexuslink.protocol.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live HashiCorp Vault round-trips against the local {@code test-env} dev-mode Vault.
 * <pre>docker compose -f test-env/docker-compose.yml up -d vault</pre>
 * Run with {@code -Dnexuslink.it=true}. Dev server: http://localhost:8200, root token {@code root},
 * KV v2 mounted at {@code secret/}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class VaultLiveIT {

    private static final String ADDR = "http://localhost:8200";
    private static final String ROOT = "root";

    @Test
    void healthReportsInitializedAndUnsealed() {
        try (VaultService v = new VaultService()) {
            v.connectToken(ADDR, ROOT, null);
            JsonNode h = v.health();
            assertTrue(h.path("initialized").asBoolean());
            assertFalse(h.path("sealed").asBoolean());
        }
    }

    @Test
    void kv2WriteReadListDeleteRoundTrip() {
        String path = "nexus-it/app-" + System.currentTimeMillis();
        try (VaultService v = new VaultService()) {
            v.connectToken(ADDR, ROOT, null);

            v.writeKv2("secret", path, Map.of("username", "nexus", "password", "s3cr3t"));

            VaultService.KvSecret got = v.readKv2("secret", path);
            assertEquals("nexus", got.data().get("username"));
            assertEquals("s3cr3t", got.data().get("password"));
            assertTrue(got.version() >= 1);

            List<String> keys = v.listKv2("secret", "nexus-it");
            assertTrue(keys.stream().anyMatch(k -> path.endsWith(k)),
                    "listed keys should include the secret we wrote: " + keys);

            v.deleteKv2("secret", path);
            assertThrows(VaultService.VaultException.class, () -> v.readKv2("secret", path),
                    "reading a hard-deleted secret should 404");
        }
    }

    @Test
    void appRoleLoginThenReadSecret() {
        ObjectMapper json = new ObjectMapper();
        String path = "nexus-it/approle-" + System.currentTimeMillis();
        String roleId, secretId;

        // Provision AppRole using the root token via the generic request seam.
        try (VaultService root = new VaultService()) {
            root.connectToken(ADDR, ROOT, null);
            root.writeKv2("secret", path, Map.of("k", "v"));

            // Enable the approle auth method (ignore "already enabled").
            try {
                ObjectNode enable = json.createObjectNode();
                enable.put("type", "approle");
                root.request("POST", "v1/sys/auth/approle", enable);
            } catch (VaultService.VaultException ignored) { }

            // Policy granting read on our test path, and a role bound to it.
            ObjectNode policy = json.createObjectNode();
            policy.put("policy", "path \"secret/data/nexus-it/*\" { capabilities = [\"read\"] }");
            root.request("PUT", "v1/sys/policies/acl/nexus-it-read", policy);

            ObjectNode role = json.createObjectNode();
            role.put("token_policies", "nexus-it-read");
            root.request("POST", "v1/auth/approle/role/nexus-it", role);

            roleId = root.request("GET", "v1/auth/approle/role/nexus-it/role-id", null)
                    .path("data").path("role_id").asText();
            secretId = root.request("POST", "v1/auth/approle/role/nexus-it/secret-id", json.createObjectNode())
                    .path("data").path("secret_id").asText();
            assertFalse(roleId.isBlank());
            assertFalse(secretId.isBlank());
        }

        try (VaultService app = new VaultService()) {
            String clientToken = app.loginAppRole(ADDR, roleId, secretId, "approle", null);
            assertNotNull(clientToken);
            assertTrue(app.isConnected());
            assertEquals("v", app.readKv2("secret", path).data().get("k"));
        }
    }
}
