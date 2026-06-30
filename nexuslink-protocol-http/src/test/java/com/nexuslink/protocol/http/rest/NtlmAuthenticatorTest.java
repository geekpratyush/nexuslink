package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NtlmAuthenticator} against published known-answer vectors:
 * <ul>
 *   <li>RFC&nbsp;1320 Appendix A.5 — proves the hand-rolled MD4 is correct.</li>
 *   <li>MS-NLMP §4.2.4 — the canonical NTLMv2 vector (domain {@code "Domain"}, user {@code "User"},
 *       password {@code "Password"}, server challenge {@code 0123456789abcdef}, client challenge
 *       {@code aaaaaaaaaaaaaaaa}, zero timestamp) for the NTLMv2 hash and NTProofStr.</li>
 * </ul>
 */
class NtlmAuthenticatorTest {

    // MS-NLMP §4.2.4.1.3 target info: NbDomainName "Domain", NbComputerName "Server", EOL.
    private static final byte[] TARGET_INFO = hex(
            "02000c0044006f006d00610069006e00"
          + "01000c00530065007200760065007200"
          + "00000000");

    private static final byte[] SERVER_CHALLENGE = hex("0123456789abcdef");
    private static final byte[] CLIENT_CHALLENGE = hex("aaaaaaaaaaaaaaaa");
    private static final byte[] ZERO_TIMESTAMP = new byte[8];

    @Test
    void md4MatchesRfc1320AppendixA5() {
        // RFC 1320 A.5: MD4("") , MD4("a"), MD4("abc").
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0",
                hexOf(NtlmAuthenticator.md4("".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24",
                hexOf(NtlmAuthenticator.md4("a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertEquals("a448017aaf21d8525fc10ae87aa6729d",
                hexOf(NtlmAuthenticator.md4("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertEquals("d9130a8164549fe818874806e1c7014b",
                hexOf(NtlmAuthenticator.md4("message digest"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
    }

    @Test
    void ntlmv2HashMatchesMsNlmpVector() {
        byte[] hash = NtlmAuthenticator.ntlmv2Hash("User", "Domain", "Password");
        // Published NTOWFv2 for these inputs (MS-NLMP §4.2.4.1.1).
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", hexOf(hash));
    }

    @Test
    void ntProofStrMatchesMsNlmpVector() {
        byte[] hash = NtlmAuthenticator.ntlmv2Hash("User", "Domain", "Password");
        byte[] response = NtlmAuthenticator.ntlmv2Response(
                hash, SERVER_CHALLENGE, TARGET_INFO, CLIENT_CHALLENGE, ZERO_TIMESTAMP);
        // NTProofStr is the first 16 bytes of the NTLMv2 response (MS-NLMP §4.2.4.2.2).
        byte[] ntProofStr = Arrays.copyOfRange(response, 0, 16);
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", hexOf(ntProofStr));
    }

    @Test
    void type1MessageHasSignatureAndNegotiateFlags() {
        byte[] msg = Base64.getDecoder().decode(NtlmAuthenticator.type1Message("Domain", "WS"));
        assertTrue(startsWith(msg, "NTLMSSP\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        // MessageType == 1 (little-endian at offset 8).
        assertEquals(1, msg[8] & 0xFF);
        int flags = (msg[12] & 0xFF) | ((msg[13] & 0xFF) << 8)
                | ((msg[14] & 0xFF) << 16) | ((msg[15] & 0xFF) << 24);
        assertEquals(0x00000001, flags & 0x00000001); // Unicode
        assertEquals(0x00000004, flags & 0x00000004); // Request Target
        assertEquals(0x00000200, flags & 0x00000200); // NTLM
    }

    @Test
    void parseType2ExtractsServerChallengeAndTargetInfo() {
        String challenge = buildType2(SERVER_CHALLENGE, TARGET_INFO);
        NtlmAuthenticator.Type2 t2 = NtlmAuthenticator.parseType2(challenge);
        assertArrayEquals(SERVER_CHALLENGE, t2.serverChallenge());
        assertArrayEquals(TARGET_INFO, t2.targetInfo());
    }

    @Test
    void type3MessageEmbedsTheNtlmv2ResponseEndToEnd() {
        // Drive the full handshake from a constructed Type 2, then verify the Type 3 carries the
        // expected NTProofStr at the start of its NtChallengeResponse field.
        NtlmAuthenticator.Type2 t2 = NtlmAuthenticator.parseType2(buildType2(SERVER_CHALLENGE, TARGET_INFO));
        byte[] msg = Base64.getDecoder().decode(NtlmAuthenticator.type3Message(
                "Domain", "User", "Password", t2, "WS", CLIENT_CHALLENGE, ZERO_TIMESTAMP));
        assertEquals(3, msg[8] & 0xFF); // MessageType
        // NtChallengeResponseFields at offset 20: Len(2), MaxLen(2), Offset(4).
        int ntLen = (msg[20] & 0xFF) | ((msg[21] & 0xFF) << 8);
        int ntOff = (msg[24] & 0xFF) | ((msg[25] & 0xFF) << 8)
                | ((msg[26] & 0xFF) << 16) | ((msg[27] & 0xFF) << 24);
        byte[] ntProofStr = Arrays.copyOfRange(msg, ntOff, ntOff + 16);
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", hexOf(ntProofStr));
        assertTrue(ntLen >= 16);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Builds a minimal but well-formed Type 2 (CHALLENGE) message with the given challenge + info. */
    private static String buildType2(byte[] serverChallenge, byte[] targetInfo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("NTLMSSP\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        le32(out, 2); // MessageType
        le16(out, 0); le16(out, 0); le32(out, 48); // TargetNameFields (empty, after header)
        le32(out, 0x00800001); // NegotiateFlags (Unicode | TargetInfo)
        out.writeBytes(serverChallenge); // 8 bytes at offset 24
        out.writeBytes(new byte[8]); // Reserved
        le16(out, targetInfo.length); le16(out, targetInfo.length); le32(out, 48); // TargetInfoFields
        out.writeBytes(targetInfo);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF); out.write((v >>> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF); out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF); out.write((v >>> 24) & 0xFF);
    }

    private static boolean startsWith(byte[] msg, byte[] prefix) {
        if (msg.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (msg[i] != prefix[i]) return false;
        return true;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
