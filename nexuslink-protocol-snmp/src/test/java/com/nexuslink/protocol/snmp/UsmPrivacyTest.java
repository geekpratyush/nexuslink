package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;

import com.nexuslink.protocol.snmp.UsmPrivacy.Encrypted;
import com.nexuslink.protocol.snmp.UsmSecurity.AuthProtocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the pure {@link UsmPrivacy} DES-CBC (RFC&nbsp;3414 §8) and AES-128-CFB (RFC&nbsp;3826)
 * crypto. Coverage is threefold: (1) every operation round-trips (encrypt then decrypt reproduces the
 * plaintext); (2) the derived privacy parameters, IV construction and RFC padding rules hold; and
 * (3) frozen known-answer vectors pin the exact ciphertext so a future refactor cannot silently change
 * the wire bytes. The privacy-localized key is the RFC&nbsp;3414 §A.3.1 {@code maplesyrup}/MD5 key, so
 * these vectors are reproducible from the published inputs and the algorithms alone — no live engine.
 */
class UsmPrivacyTest {

    /** RFC 3414 §A.3 authoritative engine ID. */
    private static final byte[] ENGINE_ID = hex("000000000000000000000002");

    /** RFC 3414 §A.3.1 localized MD5 key for "maplesyrup" — 16 octets, valid for DES and AES-128. */
    private static final byte[] LOCALIZED_KEY =
            UsmSecurity.passwordToLocalizedKey(AuthProtocol.MD5, "maplesyrup", ENGINE_ID);

    private static final int ENGINE_BOOTS = 0x00000001;
    private static final int ENGINE_TIME = 0x00000abc;
    private static final int DES_LOCAL_INT = 0x00000009;
    private static final long AES_SALT = 0x0000000000000009L;

    /** A 33-octet payload — deliberately not a multiple of 8, to exercise DES padding. */
    private static final byte[] PLAINTEXT =
            "NexusLink SNMPv3 USM privacy KAT!".getBytes(StandardCharsets.UTF_8);

    // ---- sanity on the shared fixture -------------------------------------------------------

    @Test
    void localizedKeyIsTheRfcVector() {
        assertArrayEquals(hex("526f5eed9fcce26f8964c2930787d82b"), LOCALIZED_KEY);
        assertEquals(16, LOCALIZED_KEY.length);
    }

    // ---- DES-CBC ----------------------------------------------------------------------------

    @Test
    void desRoundTripsBlockAlignedPlaintextExactly() {
        byte[] pt = "ABCDEFGH01234567".getBytes(StandardCharsets.UTF_8); // 16 octets, aligned
        Encrypted enc = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, pt);
        byte[] dec = UsmPrivacy.desDecrypt(LOCALIZED_KEY, enc.privParameters(), enc.ciphertext());
        assertArrayEquals(pt, dec);
    }

    @Test
    void desPadsToBlockAndDecryptRecoversPrefix() {
        Encrypted enc = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT);
        // 33 octets padded up to 40 (next multiple of 8).
        assertEquals(40, enc.ciphertext().length);
        assertEquals(0, enc.ciphertext().length % UsmPrivacy.DES_BLOCK_LENGTH);

        byte[] dec = UsmPrivacy.desDecrypt(LOCALIZED_KEY, enc.privParameters(), enc.ciphertext());
        assertEquals(40, dec.length);
        assertArrayEquals(PLAINTEXT, Arrays.copyOf(dec, PLAINTEXT.length));
    }

    @Test
    void desSaltIsBootsThenLocalInt() {
        Encrypted enc = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT);
        assertArrayEquals(hex("0000000100000009"), enc.privParameters());
        assertEquals(UsmPrivacy.PRIV_PARAMS_LENGTH, enc.privParameters().length);
    }

    @Test
    void desCiphertextDiffersFromPlaintext() {
        byte[] pt = "ABCDEFGH01234567".getBytes(StandardCharsets.UTF_8);
        Encrypted enc = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, pt);
        assertFalse(Arrays.equals(pt, enc.ciphertext()));
    }

    @Test
    void desMatchesFrozenKnownAnswerVectors() {
        Encrypted enc = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT);
        assertArrayEquals(
                hex("19db146b6bcb14b16e322b9b544d364a074e548b30ef776265509caf60d4c5204eadba0072806717"),
                enc.ciphertext());

        byte[] aligned = "ABCDEFGH01234567".getBytes(StandardCharsets.UTF_8);
        Encrypted enc8 = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, aligned);
        assertArrayEquals(hex("6608ddc76d9f31023a65ec8340553c0d"), enc8.ciphertext());
    }

    @Test
    void desIsDeterministicForFixedInputs() {
        Encrypted a = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT);
        Encrypted b = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT);
        assertArrayEquals(a.ciphertext(), b.ciphertext());
    }

    @Test
    void desDifferentSaltYieldsDifferentCiphertext() {
        byte[] pt = "ABCDEFGH01234567".getBytes(StandardCharsets.UTF_8);
        Encrypted a = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, pt);
        Encrypted b = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT + 1, pt);
        assertFalse(Arrays.equals(a.ciphertext(), b.ciphertext()));
    }

    @Test
    void desDecryptWithWrongSaltCorruptsFirstBlock() {
        byte[] pt = "ABCDEFGH01234567".getBytes(StandardCharsets.UTF_8);
        Encrypted enc = UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, pt);
        byte[] wrong = enc.privParameters();
        wrong[0] ^= 0x01; // CBC: a wrong IV garbles the first plaintext block
        byte[] dec = UsmPrivacy.desDecrypt(LOCALIZED_KEY, wrong, enc.ciphertext());
        assertFalse(Arrays.equals(pt, dec));
    }

    // ---- AES-128-CFB ------------------------------------------------------------------------

    @Test
    void aesRoundTripsExactlyWithoutPadding() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        // CFB is a stream mode: ciphertext keeps the plaintext length.
        assertEquals(PLAINTEXT.length, enc.ciphertext().length);
        byte[] dec = UsmPrivacy.aesDecrypt(
                LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, enc.privParameters(), enc.ciphertext());
        assertArrayEquals(PLAINTEXT, dec);
    }

    @Test
    void aesSaltIsTheSixtyFourBitValue() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        assertArrayEquals(hex("0000000000000009"), enc.privParameters());
        assertEquals(UsmPrivacy.PRIV_PARAMS_LENGTH, enc.privParameters().length);
    }

    @Test
    void aesCiphertextDiffersFromPlaintext() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        assertFalse(Arrays.equals(PLAINTEXT, enc.ciphertext()));
    }

    @Test
    void aesMatchesFrozenKnownAnswerVector() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        assertArrayEquals(
                hex("a767b8ada23e277aed00761d8a24a0e12d76da2dc119ada05bcdbcdaba6be20eff"),
                enc.ciphertext());
    }

    @Test
    void aesDifferentEngineTimeYieldsDifferentCiphertext() {
        Encrypted a = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        Encrypted b = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME + 1, AES_SALT, PLAINTEXT);
        assertFalse(Arrays.equals(a.ciphertext(), b.ciphertext()));
    }

    @Test
    void aesDecryptWithWrongEngineTimeFails() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        byte[] dec = UsmPrivacy.aesDecrypt(
                LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME + 1, enc.privParameters(), enc.ciphertext());
        assertFalse(Arrays.equals(PLAINTEXT, dec));
    }

    @Test
    void aesHandlesEmptyPlaintext() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, new byte[0]);
        assertEquals(0, enc.ciphertext().length);
        byte[] dec = UsmPrivacy.aesDecrypt(
                LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, enc.privParameters(), enc.ciphertext());
        assertEquals(0, dec.length);
    }

    // ---- record and validation --------------------------------------------------------------

    @Test
    void encryptedRecordDefensivelyCopies() {
        Encrypted enc = UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT);
        byte[] ct = enc.ciphertext();
        ct[0] ^= 0xFF; // mutating the returned copy must not affect the record
        assertFalse(Arrays.equals(ct, enc.ciphertext()));

        byte[] salt = enc.privParameters();
        salt[0] ^= 0xFF;
        assertArrayEquals(hex("0000000000000009"), enc.privParameters());
    }

    @Test
    void shortLocalizedKeyIsRejected() {
        byte[] shortKey = new byte[15];
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.desEncrypt(shortKey, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.aesEncrypt(shortKey, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, PLAINTEXT));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.desEncrypt(null, ENGINE_BOOTS, DES_LOCAL_INT, PLAINTEXT));
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.desEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, DES_LOCAL_INT, null));
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.aesEncrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, AES_SALT, null));
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.aesDecrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, null, new byte[16]));
    }

    @Test
    void desDecryptRejectsMisalignedCiphertext() {
        byte[] salt = hex("0000000100000009");
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.desDecrypt(LOCALIZED_KEY, salt, new byte[7]));
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.desDecrypt(LOCALIZED_KEY, salt, new byte[0]));
    }

    @Test
    void decryptRejectsWrongLengthSalt() {
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.desDecrypt(LOCALIZED_KEY, new byte[7], new byte[8]));
        assertThrows(IllegalArgumentException.class,
                () -> UsmPrivacy.aesDecrypt(LOCALIZED_KEY, ENGINE_BOOTS, ENGINE_TIME, new byte[9], new byte[8]));
    }

    /** Parses a hex string into its byte array (two hex chars per byte, no separators). */
    private static byte[] hex(String s) {
        int len = s.length();
        if (len % 2 != 0) throw new IllegalArgumentException("odd-length hex: " + s);
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex: " + s);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
