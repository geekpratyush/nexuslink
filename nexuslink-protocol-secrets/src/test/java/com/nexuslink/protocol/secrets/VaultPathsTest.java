package com.nexuslink.protocol.secrets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for KV v2 path construction and address normalisation — no server needed. */
class VaultPathsTest {

    @Test
    void normalizeStripsTrailingSlashesAndTrims() {
        assertEquals("http://localhost:8200", VaultPaths.normalizeAddress("  http://localhost:8200/// "));
        assertEquals("https://vault.example.com", VaultPaths.normalizeAddress("https://vault.example.com"));
    }

    @Test
    void normalizeRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> VaultPaths.normalizeAddress("   "));
        assertThrows(IllegalArgumentException.class, () -> VaultPaths.normalizeAddress(null));
    }

    @Test
    void kv2DataPathRoutesThroughDataSegment() {
        assertEquals("v1/secret/data/app/db", VaultPaths.kv2DataPath("secret", "app/db"));
        // leading/trailing slashes on both mount and path are tolerated
        assertEquals("v1/secret/data/app/db", VaultPaths.kv2DataPath("/secret/", "/app/db/"));
    }

    @Test
    void kv2DataPathDefaultsMountToSecret() {
        assertEquals("v1/secret/data/foo", VaultPaths.kv2DataPath("", "foo"));
        assertEquals("v1/secret/data/foo", VaultPaths.kv2DataPath(null, "foo"));
    }

    @Test
    void kv2MetadataPathForListAndDelete() {
        assertEquals("v1/kv/metadata/team", VaultPaths.kv2MetadataPath("kv", "team"));
        // an empty path lists the mount root
        assertEquals("v1/secret/metadata", VaultPaths.kv2MetadataPath("secret", ""));
    }

    @Test
    void approleLoginPathDefaultsMount() {
        assertEquals("v1/auth/approle/login", VaultPaths.approleLoginPath(null));
        assertEquals("v1/auth/approle/login", VaultPaths.approleLoginPath("approle"));
        assertEquals("v1/auth/my-approle/login", VaultPaths.approleLoginPath("my-approle"));
    }
}
