package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;

import com.nexuslink.protocol.snmp.UsmSecurity.AuthProtocol;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the pure {@link UsmSecurity} crypto core against the published RFC&nbsp;3414 Appendix&nbsp;A.3
 * test vectors. These are deterministic, so the derived keys must match the RFC byte-for-byte; no
 * live USM engine is involved.
 */
class UsmSecurityTest {

    /** The RFC 3414 Appendix A.3 passphrase. */
    private static final String PASSWORD = "maplesyrup";

    /** The RFC 3414 Appendix A.3 authoritative engine ID (12 bytes). */
    private static final byte[] ENGINE_ID =
            hex("000000000000000000000002");

    @Test
    void md5PasswordToKeyMatchesRfc3414A31() {
        byte[] ku = UsmSecurity.passwordToKey(AuthProtocol.MD5, PASSWORD);
        assertArrayEquals(hex("9faf3283884e92834ebc9847d8edd963"), ku);
    }

    @Test
    void md5LocalizedKeyMatchesRfc3414A31() {
        byte[] ku = UsmSecurity.passwordToKey(AuthProtocol.MD5, PASSWORD);
        byte[] kul = UsmSecurity.localizeKey(AuthProtocol.MD5, ku, ENGINE_ID);
        assertArrayEquals(hex("526f5eed9fcce26f8964c2930787d82b"), kul);
    }

    @Test
    void sha1PasswordToKeyMatchesRfc3414A32() {
        // RFC 3414 §A.3.2 published Ku for "maplesyrup". The byte at offset 18 is 0x52; this is
        // confirmed by the §A.3.2 localized key below deriving from it exactly.
        byte[] ku = UsmSecurity.passwordToKey(AuthProtocol.SHA1, PASSWORD);
        assertArrayEquals(hex("9fb5cc0381497b3793528939ff788d5d79145211"), ku);
    }

    @Test
    void sha1LocalizedKeyMatchesRfc3414A32() {
        byte[] ku = UsmSecurity.passwordToKey(AuthProtocol.SHA1, PASSWORD);
        byte[] kul = UsmSecurity.localizeKey(AuthProtocol.SHA1, ku, ENGINE_ID);
        assertArrayEquals(hex("6695febc9288e36282235fc7151f128497b38f3f"), kul);
    }

    @Test
    void passwordToLocalizedKeyConvenienceMatchesTheTwoStepForm() {
        byte[] direct = UsmSecurity.passwordToLocalizedKey(AuthProtocol.SHA1, PASSWORD, ENGINE_ID);
        assertArrayEquals(hex("6695febc9288e36282235fc7151f128497b38f3f"), direct);
    }

    @Test
    void keyLengthsAreDigestSized() {
        assertEquals(16, UsmSecurity.passwordToKey(AuthProtocol.MD5, PASSWORD).length);
        assertEquals(20, UsmSecurity.passwordToKey(AuthProtocol.SHA1, PASSWORD).length);
    }

    @Test
    void authParametersAreTwelveBytesAndDeterministic() {
        byte[] kul = UsmSecurity.passwordToLocalizedKey(AuthProtocol.MD5, PASSWORD, ENGINE_ID);
        byte[] message = "the quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

        byte[] params = UsmSecurity.authParameters(AuthProtocol.MD5, kul, message);
        byte[] again = UsmSecurity.authParameters(AuthProtocol.MD5, kul, message);

        assertEquals(UsmSecurity.AUTH_PARAM_LENGTH, params.length);
        assertEquals(12, params.length);
        assertArrayEquals(params, again);
    }

    @Test
    void sha1AuthParametersAreTwelveBytesAndStable() {
        byte[] kul = UsmSecurity.passwordToLocalizedKey(AuthProtocol.SHA1, PASSWORD, ENGINE_ID);
        byte[] message = hex("0102030405060708090a0b0c0d0e0f10");

        byte[] params = UsmSecurity.authParameters(AuthProtocol.SHA1, kul, message);
        assertEquals(12, params.length);
        assertArrayEquals(params, UsmSecurity.authParameters(AuthProtocol.SHA1, kul, message));
    }

    @Test
    void differentProtocolsProduceDifferentKeys() {
        byte[] md5 = UsmSecurity.passwordToKey(AuthProtocol.MD5, PASSWORD);
        byte[] sha1 = UsmSecurity.passwordToKey(AuthProtocol.SHA1, PASSWORD);
        assertEquals(16, md5.length);
        assertEquals(20, sha1.length);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.passwordToKey(null, PASSWORD));
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.passwordToKey(AuthProtocol.MD5, null));
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.passwordToKey(AuthProtocol.MD5, ""));
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.localizeKey(AuthProtocol.MD5, null, ENGINE_ID));
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.localizeKey(AuthProtocol.MD5, new byte[16], null));
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.authParameters(AuthProtocol.MD5, null, new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> UsmSecurity.authParameters(AuthProtocol.MD5, new byte[16], null));
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
