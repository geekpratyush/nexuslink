package com.nexuslink.security.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces and restores portable, passphrase-encrypted backups of a {@link CredentialVault}.
 * <p>
 * A backup is intentionally <em>distinct</em> from the live vault file written by
 * {@link VaultStore}: it can be written to any path, carries a small self-describing header
 * (format id, schema {@code version}, creation {@code timestamp}) so future versions can
 * migrate it, and is re-encrypted under a backup passphrase that is independent of the
 * vault's own master password.
 * <p>
 * This service is a thin orchestration layer — it owns no cryptography. Entries are
 * re-encrypted by handing plaintext to a fresh {@link CredentialVault} created with the
 * backup passphrase, so all AES-256-GCM + PBKDF2 logic continues to live in
 * {@link CredentialVault}. Import verifies the passphrase (and integrity, via the GCM
 * authentication tag) before returning; a wrong passphrase or a tampered/corrupt file
 * always fails with {@link VaultBackupException} rather than yielding a partial vault.
 */
public final class VaultBackupService {

    /** Logical format identifier embedded in every backup, used to reject foreign files. */
    static final String FORMAT_ID = "nexuslink-vault-backup";
    /** Current backup schema version. Bump when the on-disk layout changes incompatibly. */
    static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Export an encrypted, self-contained backup of {@code vault} to {@code destination}.
     * <p>
     * The source vault must be unlocked: its secrets are read in the clear and re-encrypted
     * under {@code passphrase}, so the resulting file can later be opened with that passphrase
     * regardless of the vault's original master password.
     *
     * @throws VaultBackupException        if the backup cannot be written
     * @throws CredentialVault.VaultException if {@code vault} is locked
     */
    public void exportBackup(CredentialVault vault, Path destination, char[] passphrase)
            throws VaultBackupException {
        CredentialVault backup = CredentialVault.create(passphrase);
        for (String ref : vault.refs()) {
            // retrieve() requires the source vault to be unlocked.
            String secret = vault.retrieve(ref).orElse(null);
            if (secret != null) {
                backup.store(ref, secret);
            }
        }

        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT_ID);
        root.put("version", FORMAT_VERSION);
        root.put("createdAt", Instant.now().toString());
        root.put("kdf", "PBKDF2WithHmacSHA256");
        root.put("cipher", "AES/GCM/NoPadding");
        root.put("salt", Base64.getEncoder().encodeToString(backup.saltBytes()));
        ObjectNode entries = root.putObject("entries");
        backup.encryptedEntries().forEach(entries::put);

        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.write(destination, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        } catch (IOException e) {
            throw new VaultBackupException("Failed to write vault backup to " + destination, e);
        }
    }

    /**
     * Read and decrypt a backup from {@code source}, returning a fully reconstructed,
     * <em>unlocked</em> {@link CredentialVault}.
     *
     * @throws VaultBackupException if the file is missing/unreadable, is not a recognised
     *                              backup, has an unsupported version, has been tampered
     *                              with, or the passphrase is wrong
     */
    public CredentialVault importBackup(Path source, char[] passphrase) throws VaultBackupException {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readAllBytes(source));
        } catch (IOException e) {
            throw new VaultBackupException("Backup file is missing or not valid JSON: " + source, e);
        }
        if (root == null || !root.isObject()) {
            throw new VaultBackupException("Backup file is empty or malformed: " + source);
        }

        JsonNode formatNode = root.get("format");
        if (formatNode == null || !FORMAT_ID.equals(formatNode.asText())) {
            throw new VaultBackupException("Not a NexusLink vault backup: " + source);
        }
        JsonNode versionNode = root.get("version");
        if (versionNode == null || versionNode.asInt(-1) != FORMAT_VERSION) {
            throw new VaultBackupException("Unsupported backup version in " + source
                    + " (expected " + FORMAT_VERSION + ")");
        }

        byte[] salt;
        Map<String, String> entries = new LinkedHashMap<>();
        try {
            JsonNode saltNode = root.get("salt");
            if (saltNode == null) {
                throw new VaultBackupException("Backup is missing its salt: " + source);
            }
            salt = Base64.getDecoder().decode(saltNode.asText());
            JsonNode entriesNode = root.get("entries");
            if (entriesNode != null) {
                entriesNode.fields().forEachRemaining(e -> entries.put(e.getKey(), e.getValue().asText()));
            }
        } catch (IllegalArgumentException e) {
            throw new VaultBackupException("Backup contains corrupt (non-base64) data: " + source, e);
        }

        CredentialVault vault = CredentialVault.fromPersisted(salt, entries);
        // unlock() decrypts an entry to verify both the passphrase and (via the GCM tag)
        // file integrity; a wrong passphrase or any tampering makes this return false.
        if (!vault.unlock(passphrase)) {
            throw new VaultBackupException(
                    "Wrong passphrase or corrupt backup — could not decrypt " + source);
        }
        return vault;
    }

    /**
     * Import a backup and merge its entries into an existing, unlocked {@code target} vault.
     * <p>
     * For each backed-up reference, if {@code target} already holds that key the entry is
     * only replaced when {@code overwriteExisting} is {@code true}; otherwise the existing
     * value is kept. Keys absent from {@code target} are always added.
     *
     * @return the number of entries written into {@code target}
     * @throws VaultBackupException if the backup cannot be imported (see {@link #importBackup})
     */
    public int restoreInto(CredentialVault target, Path source, char[] passphrase,
                           boolean overwriteExisting) throws VaultBackupException {
        CredentialVault backup = importBackup(source, passphrase);
        int written = 0;
        for (String ref : backup.refs()) {
            if (target.contains(ref) && !overwriteExisting) {
                continue;
            }
            String secret = backup.retrieve(ref).orElse(null);
            if (secret != null) {
                target.store(ref, secret);
                written++;
            }
        }
        return written;
    }
}
