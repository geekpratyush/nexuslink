package com.nexuslink.protocol.snmp;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The privacy (encryption) half of the SNMPv3 User-based Security Model, complementing the auth-only
 * {@link UsmSecurity}. It implements the two classic USM privacy protocols using nothing but the JDK
 * {@code javax.crypto} stack, so — like {@link UsmSecurity} — every operation is pure, deterministic
 * and fully unit-testable offline:
 *
 * <ul>
 *   <li><b>DES-CBC</b> (RFC&nbsp;3414 §8). The auth-localized key supplies both halves of the DES
 *       material: bytes 0–7 are the DES key and bytes 8–15 are the {@code pre-IV}. The 8-octet salt
 *       (engine boots &#8214; a local integer) travels in {@code msgPrivacyParameters}; the actual CBC
 *       IV is {@code pre-IV XOR salt}. Plaintext is padded up to a multiple of the 8-octet block and
 *       encrypted with {@code DES/CBC/NoPadding}.</li>
 *   <li><b>AES-128-CFB</b> (RFC&nbsp;3826). The first 16 octets of the localized key are the AES key.
 *       The 128-bit IV is {@code engineBoots(4) || engineTime(4) || salt(8)}, where the 64-bit salt is
 *       what travels in {@code msgPrivacyParameters}. Encryption uses {@code AES/CFB/NoPadding} (128-bit
 *       feedback), a stream mode, so ciphertext and plaintext share the same length.</li>
 * </ul>
 *
 * <p>The localized key handed to these methods is the same {@code Kul} produced by
 * {@link UsmSecurity#localizeKey}/{@link UsmSecurity#passwordToLocalizedKey}; per RFC&nbsp;3414 §2.6 a
 * distinct privacy passphrase is localized exactly as an auth passphrase is, so no key derivation is
 * re-implemented here.
 */
public final class UsmPrivacy {

    /** DES block/IV size and DES key size, in octets (RFC 3414 §8). */
    public static final int DES_BLOCK_LENGTH = 8;

    /** AES-128 key size and CFB IV size, in octets (RFC 3826). */
    public static final int AES_KEY_LENGTH = 16;

    /** AES-128 block/IV size, in octets. */
    public static final int AES_BLOCK_LENGTH = 16;

    /** Length, in octets, of {@code msgPrivacyParameters} (the salt) for both protocols. */
    public static final int PRIV_PARAMS_LENGTH = 8;

    /**
     * Minimum localized-key length both protocols require: DES consumes bytes 0–15 (key + pre-IV) and
     * AES-128 consumes bytes 0–15 (key).
     */
    public static final int MIN_LOCALIZED_KEY_LENGTH = 16;

    /** USM privacy protocol selector. */
    public enum PrivProtocol {
        /** DES-CBC privacy (RFC 3414 §8). */
        DES,
        /** AES-128-CFB privacy (RFC 3826). */
        AES128
    }

    /**
     * The output of an encryption: the {@code ciphertext} plus the {@code privParameters} (the salt)
     * that the peer needs — alongside the shared localized key and, for AES, the engine boots/time —
     * to reconstruct the IV and decrypt.
     */
    public record Encrypted(byte[] ciphertext, byte[] privParameters) {
        public Encrypted {
            if (ciphertext == null) throw new IllegalArgumentException("ciphertext must not be null");
            if (privParameters == null) throw new IllegalArgumentException("privParameters must not be null");
            ciphertext = ciphertext.clone();
            privParameters = privParameters.clone();
        }

        @Override public byte[] ciphertext() { return ciphertext.clone(); }

        @Override public byte[] privParameters() { return privParameters.clone(); }
    }

    private UsmPrivacy() {}

    // ------------------------------------------------------------------------------------------
    // DES-CBC (RFC 3414 §8)
    // ------------------------------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} with DES-CBC per RFC&nbsp;3414 §8.1. The salt placed in
     * {@code msgPrivacyParameters} is {@code engineBoots || localInt} (each 4 octets, big-endian); the
     * CBC IV is {@code pre-IV XOR salt}. The plaintext is zero-padded up to a multiple of the 8-octet
     * block (the RFC leaves the pad value irrelevant since the enclosed scopedPDU is self-delimiting),
     * so the ciphertext length is the input length rounded up to the next multiple of 8.
     *
     * @param localizedKey the privacy-localized key {@code Kul}; at least
     *     {@value #MIN_LOCALIZED_KEY_LENGTH} octets are used
     * @param engineBoots the authoritative engine's boot counter (high half of the salt)
     * @param localInt a per-message local integer (low half of the salt)
     * @param plaintext the data to encrypt (the serialized scopedPDU)
     * @return the ciphertext and the 8-octet salt to advertise as {@code msgPrivacyParameters}
     * @throws IllegalArgumentException if a required argument is null or the key is too short
     */
    public static Encrypted desEncrypt(byte[] localizedKey, int engineBoots, int localInt, byte[] plaintext) {
        requireKey(localizedKey);
        if (plaintext == null) throw new IllegalArgumentException("plaintext must not be null");

        byte[] salt = new byte[PRIV_PARAMS_LENGTH];
        putInt(salt, 0, engineBoots);
        putInt(salt, 4, localInt);

        byte[] iv = desIv(localizedKey, salt);
        byte[] padded = padToBlock(plaintext, DES_BLOCK_LENGTH);
        byte[] cipher = desCrypt(Cipher.ENCRYPT_MODE, localizedKey, iv, padded);
        return new Encrypted(cipher, salt);
    }

    /**
     * Inverse of {@link #desEncrypt}: decrypts {@code ciphertext} using the shared {@code localizedKey}
     * and the {@code privParameters} (salt) that arrived with the message. The returned buffer still
     * carries any trailing pad octets added at encryption time — the enclosed scopedPDU's own length
     * fields delimit the real payload.
     *
     * @throws IllegalArgumentException if an argument is null, the key is too short, the salt is not
     *     {@value #PRIV_PARAMS_LENGTH} octets, or the ciphertext length is not a multiple of the block
     */
    public static byte[] desDecrypt(byte[] localizedKey, byte[] privParameters, byte[] ciphertext) {
        requireKey(localizedKey);
        requireSalt(privParameters);
        if (ciphertext == null) throw new IllegalArgumentException("ciphertext must not be null");
        if (ciphertext.length == 0 || ciphertext.length % DES_BLOCK_LENGTH != 0) {
            throw new IllegalArgumentException(
                    "DES ciphertext length must be a positive multiple of " + DES_BLOCK_LENGTH);
        }
        byte[] iv = desIv(localizedKey, privParameters);
        return desCrypt(Cipher.DECRYPT_MODE, localizedKey, iv, ciphertext);
    }

    /** Builds the DES CBC IV: {@code pre-IV (localizedKey[8..15]) XOR salt}. */
    private static byte[] desIv(byte[] localizedKey, byte[] salt) {
        byte[] iv = new byte[DES_BLOCK_LENGTH];
        for (int i = 0; i < DES_BLOCK_LENGTH; i++) {
            iv[i] = (byte) (localizedKey[DES_BLOCK_LENGTH + i] ^ salt[i]);
        }
        return iv;
    }

    private static byte[] desCrypt(int mode, byte[] localizedKey, byte[] iv, byte[] input) {
        try {
            byte[] keyBytes = Arrays.copyOfRange(localizedKey, 0, DES_BLOCK_LENGTH);
            Cipher cipher = Cipher.getInstance("DES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(keyBytes, "DES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DES/CBC/NoPadding unavailable", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // AES-128-CFB (RFC 3826)
    // ------------------------------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} with AES-128 in CFB mode per RFC&nbsp;3826. The first
     * {@value #AES_KEY_LENGTH} octets of {@code localizedKey} form the AES key; the 128-bit IV is
     * {@code engineBoots(4) || engineTime(4) || salt(8)}. CFB is a stream mode, so no padding is
     * applied and the ciphertext has exactly the plaintext length.
     *
     * @param localizedKey the privacy-localized key {@code Kul}; the first
     *     {@value #AES_KEY_LENGTH} octets are the AES key
     * @param engineBoots the authoritative engine's boot counter (IV octets 0–3)
     * @param engineTime the authoritative engine's time (IV octets 4–7)
     * @param salt a 64-bit per-message salt (IV octets 8–15), advertised as {@code msgPrivacyParameters}
     * @param plaintext the data to encrypt (the serialized scopedPDU)
     * @return the ciphertext and the 8-octet salt to advertise as {@code msgPrivacyParameters}
     * @throws IllegalArgumentException if a required argument is null or the key is too short
     */
    public static Encrypted aesEncrypt(byte[] localizedKey, int engineBoots, int engineTime, long salt, byte[] plaintext) {
        requireKey(localizedKey);
        if (plaintext == null) throw new IllegalArgumentException("plaintext must not be null");

        byte[] saltBytes = new byte[PRIV_PARAMS_LENGTH];
        putLong(saltBytes, 0, salt);

        byte[] iv = aesIv(engineBoots, engineTime, saltBytes);
        byte[] cipher = aesCrypt(Cipher.ENCRYPT_MODE, localizedKey, iv, plaintext);
        return new Encrypted(cipher, saltBytes);
    }

    /**
     * Inverse of {@link #aesEncrypt}: decrypts {@code ciphertext} using the shared {@code localizedKey},
     * the authoritative engine boots/time that were in force when the message was built, and the
     * {@code privParameters} (salt) that arrived with it.
     *
     * @throws IllegalArgumentException if an argument is null, the key is too short, or the salt is not
     *     {@value #PRIV_PARAMS_LENGTH} octets
     */
    public static byte[] aesDecrypt(byte[] localizedKey, int engineBoots, int engineTime, byte[] privParameters, byte[] ciphertext) {
        requireKey(localizedKey);
        requireSalt(privParameters);
        if (ciphertext == null) throw new IllegalArgumentException("ciphertext must not be null");
        byte[] iv = aesIv(engineBoots, engineTime, privParameters);
        return aesCrypt(Cipher.DECRYPT_MODE, localizedKey, iv, ciphertext);
    }

    /** Builds the AES CFB IV: {@code engineBoots(4) || engineTime(4) || salt(8)}. */
    private static byte[] aesIv(int engineBoots, int engineTime, byte[] salt) {
        byte[] iv = new byte[AES_BLOCK_LENGTH];
        putInt(iv, 0, engineBoots);
        putInt(iv, 4, engineTime);
        System.arraycopy(salt, 0, iv, 8, PRIV_PARAMS_LENGTH);
        return iv;
    }

    private static byte[] aesCrypt(int mode, byte[] localizedKey, byte[] iv, byte[] input) {
        try {
            byte[] keyBytes = Arrays.copyOfRange(localizedKey, 0, AES_KEY_LENGTH);
            Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
            cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/CFB/NoPadding unavailable", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------------------------

    /** Zero-pads {@code data} up to the next multiple of {@code block} (returns a copy). */
    private static byte[] padToBlock(byte[] data, int block) {
        int remainder = data.length % block;
        if (remainder == 0) return data.clone();
        return Arrays.copyOf(data, data.length + (block - remainder));
    }

    private static void putInt(byte[] out, int offset, int value) {
        out[offset]     = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    private static void putLong(byte[] out, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            out[offset + i] = (byte) (value >>> (56 - 8 * i));
        }
    }

    private static void requireKey(byte[] localizedKey) {
        if (localizedKey == null) throw new IllegalArgumentException("localizedKey must not be null");
        if (localizedKey.length < MIN_LOCALIZED_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "localizedKey must be at least " + MIN_LOCALIZED_KEY_LENGTH + " octets");
        }
    }

    private static void requireSalt(byte[] privParameters) {
        if (privParameters == null) throw new IllegalArgumentException("privParameters must not be null");
        if (privParameters.length != PRIV_PARAMS_LENGTH) {
            throw new IllegalArgumentException("privParameters must be " + PRIV_PARAMS_LENGTH + " octets");
        }
    }
}
