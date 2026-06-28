package com.nexuslink.security.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VaultBackupServiceTest {

    private final VaultBackupService service = new VaultBackupService();

    private static CredentialVault vaultWithEntries(char[] master) {
        CredentialVault vault = CredentialVault.create(master);
        vault.store("kafka/prod/password", "s3cr3t-token");
        vault.store("aws/access-key", "AKIA-EXAMPLE");
        vault.store("db/dsn", "jdbc:postgresql://localhost/app");
        return vault;
    }

    @Test
    void roundTripPreservesAllEntries(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        char[] passphrase = "backup-passphrase-1".toCharArray();

        CredentialVault source = vaultWithEntries("master-pass".toCharArray());
        service.exportBackup(source, backup, passphrase.clone());

        CredentialVault restored = service.importBackup(backup, passphrase.clone());
        assertFalse(restored.isLocked());
        assertEquals(source.refs(), restored.refs());
        assertEquals("s3cr3t-token", restored.retrieve("kafka/prod/password").orElseThrow());
        assertEquals("AKIA-EXAMPLE", restored.retrieve("aws/access-key").orElseThrow());
        assertEquals("jdbc:postgresql://localhost/app", restored.retrieve("db/dsn").orElseThrow());
    }

    @Test
    void backupPassphraseIsIndependentOfVaultMasterPassword(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        CredentialVault source = vaultWithEntries("the-master-password".toCharArray());
        service.exportBackup(source, backup, "a-different-backup-pass".toCharArray());

        // Opens with the backup passphrase, not the original master password.
        CredentialVault restored = service.importBackup(backup, "a-different-backup-pass".toCharArray());
        assertEquals("s3cr3t-token", restored.retrieve("kafka/prod/password").orElseThrow());

        assertThrows(VaultBackupException.class,
                () -> service.importBackup(backup, "the-master-password".toCharArray()));
    }

    @Test
    void backupCarriesVersionedHeaderAndTimestamp(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        service.exportBackup(vaultWithEntries("m".toCharArray()), backup, "p".toCharArray());

        var root = new ObjectMapper().readTree(Files.readAllBytes(backup));
        assertEquals("nexuslink-vault-backup", root.get("format").asText());
        assertEquals(1, root.get("version").asInt());
        assertTrue(root.has("createdAt"), "backup must record a creation timestamp");
        assertDoesNotThrow(() -> java.time.Instant.parse(root.get("createdAt").asText()));
        // Secrets must not be stored in plaintext anywhere in the file.
        String raw = Files.readString(backup);
        assertFalse(raw.contains("s3cr3t-token"), "secret leaked into backup file");
    }

    @Test
    void wrongPassphraseThrowsDedicatedException(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        service.exportBackup(vaultWithEntries("m".toCharArray()), backup, "right-pass".toCharArray());

        assertThrows(VaultBackupException.class,
                () -> service.importBackup(backup, "wrong-pass".toCharArray()));
    }

    @Test
    void tamperedCiphertextFailsCleanly(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        char[] pass = "pass".toCharArray();
        service.exportBackup(vaultWithEntries("m".toCharArray()), backup, pass.clone());

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(Files.readAllBytes(backup));
        ObjectNode entries = (ObjectNode) root.get("entries");
        // Flip a character in every entry's base64 ciphertext so the GCM tag no longer
        // verifies, regardless of which entry the unlock check happens to sample.
        java.util.List<String> keys = new java.util.ArrayList<>();
        entries.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            String ct = entries.get(key).asText();
            char flipped = ct.charAt(0) == 'A' ? 'B' : 'A';
            entries.put(key, flipped + ct.substring(1));
        }
        Files.write(backup, mapper.writeValueAsBytes(root));

        assertThrows(VaultBackupException.class, () -> service.importBackup(backup, pass.clone()));
    }

    @Test
    void truncatedFileFailsCleanly(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        service.exportBackup(vaultWithEntries("m".toCharArray()), backup, "p".toCharArray());
        Files.write(backup, "{ this is not valid json".getBytes());

        assertThrows(VaultBackupException.class, () -> service.importBackup(backup, "p".toCharArray()));
    }

    @Test
    void foreignFileIsRejected(@TempDir Path dir) throws Exception {
        Path notABackup = dir.resolve("other.json");
        Files.writeString(notABackup, "{\"format\":\"something-else\",\"version\":1}");

        assertThrows(VaultBackupException.class,
                () -> service.importBackup(notABackup, "p".toCharArray()));
    }

    @Test
    void missingFileFailsCleanly(@TempDir Path dir) {
        assertThrows(VaultBackupException.class,
                () -> service.importBackup(dir.resolve("nope.nlbak"), "p".toCharArray()));
    }

    @Test
    void restoreIntoKeepsExistingWhenOverwriteFalse(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        char[] pass = "p".toCharArray();
        CredentialVault source = CredentialVault.create("m".toCharArray());
        source.store("shared/key", "from-backup");
        source.store("backup/only", "new-value");
        service.exportBackup(source, backup, pass.clone());

        CredentialVault target = CredentialVault.create("t".toCharArray());
        target.store("shared/key", "existing-value");

        int written = service.restoreInto(target, backup, pass.clone(), false);
        assertEquals(1, written, "only the non-colliding entry should be written");
        assertEquals("existing-value", target.retrieve("shared/key").orElseThrow());
        assertEquals("new-value", target.retrieve("backup/only").orElseThrow());
    }

    @Test
    void restoreIntoOverwritesExistingWhenOverwriteTrue(@TempDir Path dir) throws Exception {
        Path backup = dir.resolve("vault.nlbak");
        char[] pass = "p".toCharArray();
        CredentialVault source = CredentialVault.create("m".toCharArray());
        source.store("shared/key", "from-backup");
        source.store("backup/only", "new-value");
        service.exportBackup(source, backup, pass.clone());

        CredentialVault target = CredentialVault.create("t".toCharArray());
        target.store("shared/key", "existing-value");

        int written = service.restoreInto(target, backup, pass.clone(), true);
        assertEquals(2, written, "both entries should be written when overwriting");
        assertEquals("from-backup", target.retrieve("shared/key").orElseThrow());
        assertEquals("new-value", target.retrieve("backup/only").orElseThrow());
    }
}
