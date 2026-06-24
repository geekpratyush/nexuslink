package com.nexuslink.security.vault;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local credential vault — encrypts secrets at rest with AES-256-GCM, using a key
 * derived from the user's master password via PBKDF2 (200k iterations, per-vault salt).
 * <p>
 * Each secret is independently encrypted (its own random 96-bit IV), so values can be
 * added/removed without re-encrypting the whole store. Plaintext secrets are never
 * persisted and are only returned on explicit {@link #retrieve(String)} after unlock.
 * <p>
 * Serialization (to disk) is handled by {@link VaultStore}; this class is the crypto core.
 */
public final class CredentialVault {

    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int    KDF_ITERATIONS = 200_000;
    private static final int    KEY_BITS = 256;
    private static final int    SALT_BYTES = 16;
    private static final int    IV_BYTES = 12;          // 96-bit nonce for GCM
    private static final int    GCM_TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final byte[] salt;
    private final Map<String, String> encrypted = new ConcurrentHashMap<>(); // key -> base64(iv|ct|tag)
    private SecretKey key;          // null until unlocked
    private boolean locked = true;

    private CredentialVault(byte[] salt) {
        this.salt = salt.clone();
    }

    /** Create a brand-new vault protected by {@code masterPassword}. Starts unlocked. */
    public static CredentialVault create(char[] masterPassword) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        CredentialVault vault = new CredentialVault(salt);
        vault.key = deriveKey(masterPassword, salt);
        vault.locked = false;
        return vault;
    }

    /** Reconstruct a vault from persisted salt + ciphertext map (remains locked). */
    static CredentialVault fromPersisted(byte[] salt, Map<String, String> encrypted) {
        CredentialVault vault = new CredentialVault(salt);
        vault.encrypted.putAll(encrypted);
        return vault;
    }

    /**
     * Unlock with the master password. Verifies the password by attempting to decrypt
     * one stored entry (if any). Returns true on success.
     */
    public boolean unlock(char[] masterPassword) {
        SecretKey candidate = deriveKey(masterPassword, salt);
        // Verify against any existing entry; empty vault trusts the password.
        if (!encrypted.isEmpty()) {
            String anyValue = encrypted.values().iterator().next();
            try {
                decryptWith(candidate, anyValue);
            } catch (Exception badPassword) {
                return false;
            }
        }
        this.key = candidate;
        this.locked = false;
        return true;
    }

    /** Lock the vault — clears the in-memory key. Ciphertext remains. */
    public void lock() {
        this.key = null;
        this.locked = true;
    }

    public boolean isLocked() {
        return locked;
    }

    /** Encrypt and store a secret under {@code ref}. Overwrites any existing value. */
    public void store(String ref, String secret) {
        requireUnlocked();
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            encrypted.put(ref, Base64.getEncoder().encodeToString(combined));
        } catch (Exception e) {
            throw new VaultException("Failed to encrypt secret '" + ref + "'", e);
        }
    }

    /** Decrypt and return the secret stored under {@code ref}, if present. */
    public Optional<String> retrieve(String ref) {
        requireUnlocked();
        String value = encrypted.get(ref);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(decryptWith(key, value));
        } catch (Exception e) {
            throw new VaultException("Failed to decrypt secret '" + ref + "'", e);
        }
    }

    public boolean contains(String ref) {
        return encrypted.containsKey(ref);
    }

    public void remove(String ref) {
        encrypted.remove(ref);
    }

    public java.util.Set<String> refs() {
        return java.util.Set.copyOf(encrypted.keySet());
    }

    // ---- package-private accessors for VaultStore serialization ----

    byte[] saltBytes() {
        return salt.clone();
    }

    Map<String, String> encryptedEntries() {
        return Map.copyOf(encrypted);
    }

    // ---- internals ----

    private String decryptWith(SecretKey k, String base64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(base64);
        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[combined.length - IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_BYTES);
        System.arraycopy(combined, IV_BYTES, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF);
            PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new VaultException("Key derivation failed", e);
        }
    }

    private void requireUnlocked() {
        if (locked || key == null) {
            throw new VaultException("Vault is locked — unlock with the master password first");
        }
    }

    /** Unchecked exception for vault crypto failures. */
    public static final class VaultException extends RuntimeException {
        public VaultException(String message, Throwable cause) { super(message, cause); }
        public VaultException(String message) { super(message); }
    }
}
