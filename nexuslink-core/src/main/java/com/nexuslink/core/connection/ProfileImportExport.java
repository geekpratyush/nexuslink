package com.nexuslink.core.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Exports and imports a set of {@link ConnectionProfile}s as a portable, passphrase-encrypted JSON
 * bundle — for backing up or sharing connections between machines/teammates. The profile list is
 * serialised to JSON and encrypted with AES-256-GCM under a key derived from the passphrase via
 * PBKDF2 (the same primitives the credential vault uses), so the bundle is confidential and tamper-
 * evident: a wrong passphrase or any modification fails the GCM authentication tag and throws rather
 * than yielding partial data. Self-contained ({@code javax.crypto} + Jackson only), so it is fully
 * unit-testable by round-tripping in memory.
 */
public final class ProfileImportExport {

    /** Logical format id embedded in every bundle so foreign/corrupt files are rejected early. */
    static final String FORMAT_ID = "nexuslink-profiles";
    static final int FORMAT_VERSION = 1;
    private static final int PBKDF2_ITERATIONS = 200_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** Thrown when an export/import fails (bad passphrase, tampered/foreign bundle, or I/O). */
    public static final class ProfileBundleException extends Exception {
        public ProfileBundleException(String message, Throwable cause) { super(message, cause); }
        public ProfileBundleException(String message) { super(message); }
    }

    /** Encrypts {@code profiles} under {@code passphrase} and returns the bundle as a JSON string. */
    public String export(List<ConnectionProfile> profiles, char[] passphrase) throws ProfileBundleException {
        try {
            byte[] plaintext = MAPPER.writeValueAsBytes(profiles);
            byte[] salt = randomBytes(SALT_BYTES);
            byte[] iv = randomBytes(IV_BYTES);
            SecretKeySpec key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            ObjectNode root = MAPPER.createObjectNode();
            root.put("format", FORMAT_ID);
            root.put("version", FORMAT_VERSION);
            root.put("createdAt", Instant.now().toString());
            root.put("kdf", "PBKDF2WithHmacSHA256");
            root.put("iterations", PBKDF2_ITERATIONS);
            root.put("cipher", "AES/GCM/NoPadding");
            root.put("count", profiles.size());
            root.put("salt", b64(salt));
            root.put("iv", b64(iv));
            root.put("ciphertext", b64(ciphertext));
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new ProfileBundleException("Failed to export profile bundle: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts a bundle produced by {@link #export}. Verifies the format id and version, then AES-GCM
     * decrypts — a wrong passphrase or any tampering fails the tag and throws.
     */
    public List<ConnectionProfile> importBundle(String bundleJson, char[] passphrase) throws ProfileBundleException {
        JsonNode root;
        try {
            root = MAPPER.readTree(bundleJson == null ? "" : bundleJson);
        } catch (Exception e) {
            throw new ProfileBundleException("Not a valid profile bundle (unreadable JSON)", e);
        }
        if (!FORMAT_ID.equals(root.path("format").asText())) {
            throw new ProfileBundleException("Not a NexusLink profile bundle");
        }
        if (root.path("version").asInt(-1) > FORMAT_VERSION) {
            throw new ProfileBundleException("Bundle was written by a newer version (" + root.path("version").asInt() + ")");
        }
        try {
            byte[] salt = unb64(root, "salt");
            byte[] iv = unb64(root, "iv");
            byte[] ciphertext = unb64(root, "ciphertext");
            int iterations = root.path("iterations").asInt(PBKDF2_ITERATIONS);
            SecretKeySpec key = deriveKey(passphrase, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return MAPPER.readValue(plaintext, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, ConnectionProfile.class));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new ProfileBundleException("Wrong passphrase, or the bundle has been tampered with", e);
        } catch (Exception e) {
            throw new ProfileBundleException("Failed to import profile bundle: " + e.getMessage(), e);
        }
    }

    private SecretKeySpec deriveKey(char[] passphrase, byte[] salt) throws Exception {
        return deriveKey(passphrase, salt, PBKDF2_ITERATIONS);
    }

    private SecretKeySpec deriveKey(char[] passphrase, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = f.generateSecret(new PBEKeySpec(passphrase, salt, iterations, KEY_BITS)).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    private static byte[] unb64(JsonNode root, String field) {
        return Base64.getDecoder().decode(root.path(field).asText());
    }
}
