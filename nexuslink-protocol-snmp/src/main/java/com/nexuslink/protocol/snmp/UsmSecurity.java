package com.nexuslink.protocol.snmp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cryptographic core of the SNMPv3 User-based Security Model (USM, RFC&nbsp;3414). It turns a
 * human passphrase into the per-engine localized key and produces the truncated HMAC that fills the
 * {@code msgAuthenticationParameters} field of an authenticated message.
 *
 * <p>Everything here is pure, deterministic and JDK-only (no external crypto provider), so it is
 * fully unit-testable offline against the published RFC&nbsp;3414 Appendix&nbsp;A test vectors. The
 * three steps are:
 *
 * <ul>
 *   <li>{@link #passwordToKey} — the §A.2 password-to-key KDF: the passphrase is cycled to fill
 *       exactly {@value #KDF_STREAM_LENGTH} bytes (1&nbsp;MB) which is then hashed once to yield the
 *       user key {@code Ku}.</li>
 *   <li>{@link #localizeKey} — the §2.6 key localization: {@code Kul = digest(Ku || engineId || Ku)},
 *       binding the key to a single authoritative engine.</li>
 *   <li>{@link #authParameters} — the HMAC auth digest (HMAC-MD5-96 / HMAC-SHA-96) of the whole
 *       message under {@code Kul}, truncated to the first {@value #AUTH_PARAM_LENGTH} bytes.</li>
 * </ul>
 *
 * <p>Privacy (encryption) is out of scope here; this class only covers the auth side of USM.
 */
public final class UsmSecurity {

    /** The size, in bytes, of the password stream hashed by the §A.2 KDF (exactly 1&nbsp;MB). */
    public static final int KDF_STREAM_LENGTH = 1_048_576;

    /** The length, in bytes, the HMAC is truncated to for {@code msgAuthenticationParameters}. */
    public static final int AUTH_PARAM_LENGTH = 12;

    /** USM authentication protocol — selects both the digest and the HMAC variant. */
    public enum AuthProtocol {
        /** HMAC-MD5-96 (RFC 3414 §A.2.1), backed by the MD5 digest. */
        MD5("MD5", "HmacMD5"),
        /** HMAC-SHA-96 (RFC 3414 §A.2.2), backed by the SHA-1 digest. */
        SHA1("SHA-1", "HmacSHA1");

        private final String digestAlgorithm;
        private final String macAlgorithm;

        AuthProtocol(String digestAlgorithm, String macAlgorithm) {
            this.digestAlgorithm = digestAlgorithm;
            this.macAlgorithm = macAlgorithm;
        }

        /** The JDK {@link MessageDigest} algorithm name ({@code MD5} / {@code SHA-1}). */
        public String digestAlgorithm() { return digestAlgorithm; }

        /** The JDK {@link Mac} algorithm name ({@code HmacMD5} / {@code HmacSHA1}). */
        public String macAlgorithm() { return macAlgorithm; }
    }

    private UsmSecurity() {}

    /**
     * Derives the user key {@code Ku} from {@code password} using the RFC&nbsp;3414 §A.2 KDF: the
     * passphrase bytes (UTF-8) are repeated to fill a stream of exactly {@value #KDF_STREAM_LENGTH}
     * bytes which is hashed once with the protocol digest. The result is 16 bytes for MD5 and 20
     * bytes for SHA-1.
     *
     * @throws IllegalArgumentException if {@code authProtocol} or {@code password} is null, or the
     *     password is empty (the cycling stream would be undefined)
     */
    public static byte[] passwordToKey(AuthProtocol authProtocol, String password) {
        requireProtocol(authProtocol);
        if (password == null) throw new IllegalArgumentException("password must not be null");
        byte[] pass = password.getBytes(StandardCharsets.UTF_8);
        if (pass.length == 0) throw new IllegalArgumentException("password must not be empty");

        MessageDigest digest = newDigest(authProtocol);
        byte[] block = new byte[64];
        int passIndex = 0;
        for (int count = 0; count < KDF_STREAM_LENGTH; count += block.length) {
            for (int i = 0; i < block.length; i++) {
                block[i] = pass[passIndex];
                passIndex = (passIndex + 1) % pass.length;
            }
            digest.update(block);
        }
        return digest.digest();
    }

    /**
     * Localizes a user key {@code key} to the authoritative engine {@code engineId} per
     * RFC&nbsp;3414 §2.6, i.e. {@code Kul = digest(key || engineId || key)}. The localized key is
     * what actually keys the HMAC.
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public static byte[] localizeKey(AuthProtocol authProtocol, byte[] key, byte[] engineId) {
        requireProtocol(authProtocol);
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (engineId == null) throw new IllegalArgumentException("engineId must not be null");

        MessageDigest digest = newDigest(authProtocol);
        digest.update(key);
        digest.update(engineId);
        digest.update(key);
        return digest.digest();
    }

    /**
     * Computes the {@code msgAuthenticationParameters} value for {@code wholeMessage}: the HMAC of
     * the message under {@code localizedKey}, truncated to the first {@value #AUTH_PARAM_LENGTH}
     * bytes (HMAC-MD5-96 / HMAC-SHA-96).
     *
     * <p>Per RFC&nbsp;3414 §6.3.1 the digest is computed over the message with the auth-parameters
     * field already present and zero-filled; the returned 12 bytes are spliced back into that field.
     *
     * @throws IllegalArgumentException if any argument is null
     */
    public static byte[] authParameters(AuthProtocol authProtocol, byte[] localizedKey, byte[] wholeMessage) {
        requireProtocol(authProtocol);
        if (localizedKey == null) throw new IllegalArgumentException("localizedKey must not be null");
        if (wholeMessage == null) throw new IllegalArgumentException("wholeMessage must not be null");

        try {
            Mac mac = Mac.getInstance(authProtocol.macAlgorithm());
            mac.init(new SecretKeySpec(localizedKey, authProtocol.macAlgorithm()));
            byte[] full = mac.doFinal(wholeMessage);
            byte[] truncated = new byte[AUTH_PARAM_LENGTH];
            System.arraycopy(full, 0, truncated, 0, AUTH_PARAM_LENGTH);
            return truncated;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC " + authProtocol.macAlgorithm() + " unavailable", e);
        }
    }

    /**
     * Convenience that chains {@link #passwordToKey} and {@link #localizeKey}: derive {@code Ku} from
     * the passphrase and localize it to {@code engineId} in one call.
     */
    public static byte[] passwordToLocalizedKey(AuthProtocol authProtocol, String password, byte[] engineId) {
        return localizeKey(authProtocol, passwordToKey(authProtocol, password), engineId);
    }

    private static MessageDigest newDigest(AuthProtocol authProtocol) {
        try {
            return MessageDigest.getInstance(authProtocol.digestAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("digest " + authProtocol.digestAlgorithm() + " unavailable", e);
        }
    }

    private static void requireProtocol(AuthProtocol authProtocol) {
        if (authProtocol == null) throw new IllegalArgumentException("authProtocol must not be null");
    }
}
