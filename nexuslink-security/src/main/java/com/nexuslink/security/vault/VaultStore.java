package com.nexuslink.security.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists a {@link CredentialVault} to (and from) a JSON file on disk.
 * Only the salt and per-entry ciphertext are written — never the master password
 * or any plaintext. The file format is intentionally simple and forward-compatible.
 */
public final class VaultStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FORMAT_VERSION = 1;

    private VaultStore() {}

    /** Write the vault's salt + ciphertext to {@code file} (creates parent dirs). */
    public static void save(CredentialVault vault, Path file) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", FORMAT_VERSION);
        root.put("kdf", "PBKDF2WithHmacSHA256");
        root.put("cipher", "AES/GCM/NoPadding");
        root.put("salt", Base64.getEncoder().encodeToString(vault.saltBytes()));
        ObjectNode entries = root.putObject("entries");
        vault.encryptedEntries().forEach(entries::put);

        if (file.getParent() != null) Files.createDirectories(file.getParent());
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        Files.write(file, bytes);
    }

    /** Load a (locked) vault from {@code file}. Call {@code unlock(...)} before use. */
    public static CredentialVault load(Path file) throws IOException {
        var root = MAPPER.readTree(Files.readAllBytes(file));
        byte[] salt = Base64.getDecoder().decode(root.get("salt").asText());
        Map<String, String> entries = new LinkedHashMap<>();
        var entriesNode = root.get("entries");
        if (entriesNode != null) {
            entriesNode.fields().forEachRemaining(e -> entries.put(e.getKey(), e.getValue().asText()));
        }
        return CredentialVault.fromPersisted(salt, entries);
    }

    public static boolean exists(Path file) {
        return Files.exists(file);
    }
}
