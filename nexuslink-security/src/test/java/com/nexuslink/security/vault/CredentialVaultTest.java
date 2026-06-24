package com.nexuslink.security.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CredentialVaultTest {

    @Test
    void storeAndRetrieveRoundTrips() {
        CredentialVault vault = CredentialVault.create("master-pass-123".toCharArray());
        vault.store("kafka/prod/password", "s3cr3t-token");
        vault.store("aws/access-key", "AKIA-EXAMPLE");

        assertEquals("s3cr3t-token", vault.retrieve("kafka/prod/password").orElseThrow());
        assertEquals("AKIA-EXAMPLE", vault.retrieve("aws/access-key").orElseThrow());
        assertTrue(vault.retrieve("does/not/exist").isEmpty());
    }

    @Test
    void persistsAndReloadsWithCorrectPassword(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vault.json");
        char[] pw = "correct horse battery staple".toCharArray();

        CredentialVault vault = CredentialVault.create(pw.clone());
        vault.store("api/token", "bearer-xyz");
        VaultStore.save(vault, file);

        CredentialVault reloaded = VaultStore.load(file);
        assertTrue(reloaded.isLocked());
        assertTrue(reloaded.unlock(pw.clone()), "unlock should succeed with correct password");
        assertEquals("bearer-xyz", reloaded.retrieve("api/token").orElseThrow());
    }

    @Test
    void wrongPasswordFailsToUnlock(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("vault.json");
        CredentialVault vault = CredentialVault.create("right-password".toCharArray());
        vault.store("secret", "value");
        VaultStore.save(vault, file);

        CredentialVault reloaded = VaultStore.load(file);
        assertFalse(reloaded.unlock("WRONG-password".toCharArray()),
                "unlock must fail with an incorrect master password");
        assertTrue(reloaded.isLocked());
    }

    @Test
    void lockedVaultRejectsAccess() {
        CredentialVault vault = CredentialVault.create("pw".toCharArray());
        vault.store("k", "v");
        vault.lock();
        assertThrows(CredentialVault.VaultException.class, () -> vault.retrieve("k"));
        assertThrows(CredentialVault.VaultException.class, () -> vault.store("k2", "v2"));
    }

    @Test
    void ciphertextDiffersFromPlaintextAndIsNonDeterministic() {
        CredentialVault vault = CredentialVault.create("pw".toCharArray());
        vault.store("a", "same-value");
        String first = vault.encryptedEntries().get("a");
        vault.store("a", "same-value"); // re-encrypt same plaintext
        String second = vault.encryptedEntries().get("a");

        assertNotEquals("same-value", first, "value must not be stored as plaintext");
        assertNotEquals(first, second, "random IV must make ciphertext non-deterministic");
    }
}
