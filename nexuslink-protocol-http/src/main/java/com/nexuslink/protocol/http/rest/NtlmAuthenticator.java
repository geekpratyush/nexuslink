package com.nexuslink.protocol.http.rest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * NTLM (NTLMv2) authentication message construction per MS-NLMP. Pure and dependency-free: it builds
 * the three handshake messages — NEGOTIATE (Type&nbsp;1), CHALLENGE (Type&nbsp;2, parsed) and
 * AUTHENTICATE (Type&nbsp;3) — and the NTLMv2 cryptographic chain that backs the Type&nbsp;3 response.
 *
 * <p>The chain is, end-to-end, JDK-only:
 * <ul>
 *   <li><b>NT hash</b> = {@code MD4(UTF-16LE(password))}. The JDK ships no MD4, so it is hand-rolled
 *       per RFC&nbsp;1320 in {@link #md4(byte[])}.</li>
 *   <li><b>NTLMv2 hash</b> (NTOWFv2) = {@code HMAC-MD5(NT hash, UTF-16LE(uppercase(user) + domain))}
 *       via {@code javax.crypto.Mac "HmacMD5"}.</li>
 *   <li><b>NTProofStr</b> = {@code HMAC-MD5(NTLMv2 hash, serverChallenge || blob)} where the blob is
 *       the MS-NLMP {@code temp} structure (fixed bytes + timestamp + client challenge + target
 *       info). The full NTLMv2 response is {@code NTProofStr || blob}.</li>
 * </ul>
 * The timestamp and client challenge can be injected so the response is deterministic for tests; the
 * crypto is verified offline against the MS-NLMP §4.2.4 known-answer vectors (see the test class).
 *
 * <p>All multi-byte fields are little-endian per MS-NLMP. This class only constructs/parses the
 * messages; wiring it into the request pipeline is done elsewhere.
 */
public final class NtlmAuthenticator {

    private NtlmAuthenticator() {}

    /** {@code "NTLMSSP\0"} message signature. */
    private static final byte[] SIGNATURE = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};

    // Negotiate flags (MS-NLMP §2.2.2.5).
    private static final int NTLMSSP_NEGOTIATE_UNICODE = 0x00000001;
    private static final int NTLMSSP_REQUEST_TARGET = 0x00000004;
    private static final int NTLMSSP_NEGOTIATE_NTLM = 0x00000200;
    private static final int NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000;
    private static final int NTLMSSP_NEGOTIATE_TARGET_INFO = 0x00800000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Parsed CHALLENGE (Type 2) message: the 8-byte server challenge and the target-info AV pairs. */
    public static final class Type2 {
        private final byte[] serverChallenge;
        private final byte[] targetInfo;
        private final int flags;

        Type2(byte[] serverChallenge, byte[] targetInfo, int flags) {
            this.serverChallenge = serverChallenge;
            this.targetInfo = targetInfo;
            this.flags = flags;
        }

        /** The 8-byte server challenge (nonce). */
        public byte[] serverChallenge() { return serverChallenge.clone(); }

        /** The raw target-info (AV pairs) block; empty when the server sent none. */
        public byte[] targetInfo() { return targetInfo.clone(); }

        /** The server's negotiate flags. */
        public int flags() { return flags; }
    }

    /**
     * Builds the NEGOTIATE (Type 1) message and returns it Base64-encoded. Negotiates Unicode, NTLM
     * and Request-Target (plus extended session security / target-info, required for NTLMv2).
     */
    public static String type1Message(String domain, String workstation) {
        byte[] dom = oem(domain);
        byte[] ws = oem(workstation);
        int flags = NTLMSSP_NEGOTIATE_UNICODE | NTLMSSP_REQUEST_TARGET | NTLMSSP_NEGOTIATE_NTLM
                | NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY | NTLMSSP_NEGOTIATE_TARGET_INFO;

        // Fixed 32-byte header, then domain + workstation payloads (OEM per the negotiate header).
        int headerLen = 32;
        int domOff = headerLen;
        int wsOff = domOff + dom.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        writeLE32(out, 1); // MessageType
        writeLE32(out, flags);
        // DomainNameFields: Len, MaxLen, BufferOffset.
        writeLE16(out, dom.length);
        writeLE16(out, dom.length);
        writeLE32(out, domOff);
        // WorkstationFields.
        writeLE16(out, ws.length);
        writeLE16(out, ws.length);
        writeLE32(out, wsOff);
        out.writeBytes(dom);
        out.writeBytes(ws);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Parses a Base64-encoded CHALLENGE (Type 2) message. */
    public static Type2 parseType2(String base64Challenge) {
        byte[] msg = Base64.getDecoder().decode(base64Challenge.trim());
        if (msg.length < 32 || !startsWith(msg, SIGNATURE)) {
            throw new IllegalArgumentException("Not an NTLM message");
        }
        if (readLE32(msg, 8) != 2) {
            throw new IllegalArgumentException("Not a Type 2 (CHALLENGE) message");
        }
        int flags = readLE32(msg, 20);
        byte[] serverChallenge = Arrays.copyOfRange(msg, 24, 32);

        byte[] targetInfo = new byte[0];
        // TargetInfoFields live at offset 40 (Len, MaxLen at 40/42, BufferOffset at 44).
        if (msg.length >= 48) {
            int tiLen = readLE16(msg, 40);
            int tiOff = readLE32(msg, 44);
            if (tiLen > 0 && tiOff >= 0 && tiOff + tiLen <= msg.length) {
                targetInfo = Arrays.copyOfRange(msg, tiOff, tiOff + tiLen);
            }
        }
        return new Type2(serverChallenge, targetInfo, flags);
    }

    /**
     * Builds the AUTHENTICATE (Type 3) message carrying the NTLMv2 response, Base64-encoded. Uses a
     * random client challenge and the current time.
     */
    public static String type3Message(String domain, String user, String password,
                                      Type2 challenge, String workstation) {
        byte[] clientChallenge = new byte[8];
        RANDOM.nextBytes(clientChallenge);
        return type3Message(domain, user, password, challenge, workstation,
                clientChallenge, currentTimestamp());
    }

    /**
     * Deterministic variant: the 8-byte {@code clientChallenge} and 8-byte little-endian
     * {@code timestamp} are injected (used by tests to reproduce the published vectors).
     */
    public static String type3Message(String domain, String user, String password,
                                      Type2 challenge, String workstation,
                                      byte[] clientChallenge, byte[] timestamp) {
        byte[] ntlmv2Hash = ntlmv2Hash(user, domain, password);
        byte[] ntResponse = ntlmv2Response(ntlmv2Hash, challenge.serverChallenge(),
                challenge.targetInfo(), clientChallenge, timestamp);
        byte[] lmResponse = lmv2Response(ntlmv2Hash, challenge.serverChallenge(), clientChallenge);

        byte[] domainBytes = unicode(domain);
        byte[] userBytes = unicode(user);
        byte[] wsBytes = unicode(workstation);

        int flags = NTLMSSP_NEGOTIATE_UNICODE | NTLMSSP_REQUEST_TARGET | NTLMSSP_NEGOTIATE_NTLM
                | NTLMSSP_NEGOTIATE_EXTENDED_SESSIONSECURITY | NTLMSSP_NEGOTIATE_TARGET_INFO;

        // 64-byte header (no Version/MIC), then payload in this order.
        int header = 64;
        int domOff = header;
        int userOff = domOff + domainBytes.length;
        int wsOff = userOff + userBytes.length;
        int lmOff = wsOff + wsBytes.length;
        int ntOff = lmOff + lmResponse.length;
        int sessKeyOff = ntOff + ntResponse.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SIGNATURE);
        writeLE32(out, 3); // MessageType
        // LmChallengeResponseFields.
        writeLE16(out, lmResponse.length);
        writeLE16(out, lmResponse.length);
        writeLE32(out, lmOff);
        // NtChallengeResponseFields.
        writeLE16(out, ntResponse.length);
        writeLE16(out, ntResponse.length);
        writeLE32(out, ntOff);
        // DomainNameFields.
        writeLE16(out, domainBytes.length);
        writeLE16(out, domainBytes.length);
        writeLE32(out, domOff);
        // UserNameFields.
        writeLE16(out, userBytes.length);
        writeLE16(out, userBytes.length);
        writeLE32(out, userOff);
        // WorkstationFields.
        writeLE16(out, wsBytes.length);
        writeLE16(out, wsBytes.length);
        writeLE32(out, wsOff);
        // EncryptedRandomSessionKeyFields (none).
        writeLE16(out, 0);
        writeLE16(out, 0);
        writeLE32(out, sessKeyOff);
        // NegotiateFlags.
        writeLE32(out, flags);

        out.writeBytes(domainBytes);
        out.writeBytes(userBytes);
        out.writeBytes(wsBytes);
        out.writeBytes(lmResponse);
        out.writeBytes(ntResponse);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // ---- NTLMv2 crypto (exposed for known-answer testing) -------------------------------------

    /** NTLMv2 hash (NTOWFv2) = HMAC-MD5(MD4(UTF-16LE(password)), UTF-16LE(UPPER(user) + domain)). */
    public static byte[] ntlmv2Hash(String user, String domain, String password) {
        byte[] ntHash = md4(unicode(password));
        byte[] identity = unicode((user == null ? "" : user).toUpperCase() + (domain == null ? "" : domain));
        return hmacMd5(ntHash, identity);
    }

    /**
     * Full NTLMv2 response = {@code NTProofStr || blob}, where
     * {@code NTProofStr = HMAC-MD5(ntlmv2Hash, serverChallenge || blob)} and the blob is the MS-NLMP
     * {@code temp} structure. The first 16 bytes are the NTProofStr.
     */
    public static byte[] ntlmv2Response(byte[] ntlmv2Hash, byte[] serverChallenge, byte[] targetInfo,
                                        byte[] clientChallenge, byte[] timestamp) {
        byte[] blob = temp(timestamp, clientChallenge, targetInfo);
        byte[] proof = hmacMd5(ntlmv2Hash, concat(serverChallenge, blob));
        return concat(proof, blob);
    }

    /** LMv2 response = HMAC-MD5(ntlmv2Hash, serverChallenge || clientChallenge) || clientChallenge. */
    public static byte[] lmv2Response(byte[] ntlmv2Hash, byte[] serverChallenge, byte[] clientChallenge) {
        byte[] mac = hmacMd5(ntlmv2Hash, concat(serverChallenge, clientChallenge));
        return concat(mac, clientChallenge);
    }

    /** MS-NLMP {@code temp} blob: 0x01 0x01, Z(6), Time(8), ClientChallenge(8), Z(4), TargetInfo, Z(4). */
    private static byte[] temp(byte[] timestamp, byte[] clientChallenge, byte[] targetInfo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x01); // Responserversion
        out.write(0x01); // HiResponserversion
        out.writeBytes(new byte[6]); // Z(6)
        out.writeBytes(fixed(timestamp, 8)); // Time
        out.writeBytes(fixed(clientChallenge, 8)); // ClientChallenge
        out.writeBytes(new byte[4]); // Z(4)
        out.writeBytes(targetInfo == null ? new byte[0] : targetInfo);
        out.writeBytes(new byte[4]); // Z(4)
        return out.toByteArray();
    }

    // ---- MD4 (RFC 1320), hand-rolled (the JDK ships no MD4) ------------------------------------

    /** Computes the MD4 digest of {@code message} per RFC 1320. */
    public static byte[] md4(byte[] message) {
        // Padding: 0x80, then zeros to 56 mod 64, then the 64-bit little-endian bit length.
        long bitLen = (long) message.length * 8;
        int padLen = ((56 - (message.length + 1) % 64) + 64) % 64;
        byte[] padded = new byte[message.length + 1 + padLen + 8];
        System.arraycopy(message, 0, padded, 0, message.length);
        padded[message.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            padded[padded.length - 8 + i] = (byte) (bitLen >>> (8 * i));
        }

        int a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        int[] x = new int[16];
        for (int off = 0; off < padded.length; off += 64) {
            for (int j = 0; j < 16; j++) {
                x[j] = (padded[off + j * 4] & 0xFF)
                        | ((padded[off + j * 4 + 1] & 0xFF) << 8)
                        | ((padded[off + j * 4 + 2] & 0xFF) << 16)
                        | ((padded[off + j * 4 + 3] & 0xFF) << 24);
            }
            int aa = a, bb = b, cc = c, dd = d;

            // Round 1: F(x,y,z) = (x & y) | (~x & z); shifts 3,7,11,19.
            int[] r1s = {3, 7, 11, 19};
            for (int j = 0; j < 16; j += 4) {
                a = rotl(a + f(b, c, d) + x[j], r1s[0]);
                d = rotl(d + f(a, b, c) + x[j + 1], r1s[1]);
                c = rotl(c + f(d, a, b) + x[j + 2], r1s[2]);
                b = rotl(b + f(c, d, a) + x[j + 3], r1s[3]);
            }

            // Round 2: G(x,y,z) = (x & y) | (x & z) | (y & z); +5A827999; shifts 3,5,9,13.
            int[] r2k = {0, 4, 8, 12};
            int[] r2s = {3, 5, 9, 13};
            for (int j = 0; j < 4; j++) {
                a = rotl(a + g(b, c, d) + x[r2k[0] + j] + 0x5A827999, r2s[0]);
                d = rotl(d + g(a, b, c) + x[r2k[1] + j] + 0x5A827999, r2s[1]);
                c = rotl(c + g(d, a, b) + x[r2k[2] + j] + 0x5A827999, r2s[2]);
                b = rotl(b + g(c, d, a) + x[r2k[3] + j] + 0x5A827999, r2s[3]);
            }

            // Round 3: H(x,y,z) = x ^ y ^ z; +6ED9EBA1; shifts 3,9,11,15.
            int[] r3k = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
            int[] r3s = {3, 9, 11, 15};
            for (int j = 0; j < 16; j += 4) {
                a = rotl(a + h(b, c, d) + x[r3k[j]] + 0x6ED9EBA1, r3s[0]);
                d = rotl(d + h(a, b, c) + x[r3k[j + 1]] + 0x6ED9EBA1, r3s[1]);
                c = rotl(c + h(d, a, b) + x[r3k[j + 2]] + 0x6ED9EBA1, r3s[2]);
                b = rotl(b + h(c, d, a) + x[r3k[j + 3]] + 0x6ED9EBA1, r3s[3]);
            }

            a += aa; b += bb; c += cc; d += dd;
        }

        byte[] out = new byte[16];
        writeLE32(out, 0, a);
        writeLE32(out, 4, b);
        writeLE32(out, 8, c);
        writeLE32(out, 12, d);
        return out;
    }

    private static int f(int x, int y, int z) { return (x & y) | (~x & z); }
    private static int g(int x, int y, int z) { return (x & y) | (x & z) | (y & z); }
    private static int h(int x, int y, int z) { return x ^ y ^ z; }
    private static int rotl(int v, int s) { return (v << s) | (v >>> (32 - s)); }

    // ---- small helpers ------------------------------------------------------------------------

    private static byte[] hmacMd5(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacMD5"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacMD5 failure", e);
        }
    }

    private static byte[] currentTimestamp() {
        // Windows FILETIME: 100-ns ticks since 1601-01-01, little-endian.
        long ticks = (System.currentTimeMillis() + 11644473600000L) * 10000L;
        byte[] ts = new byte[8];
        for (int i = 0; i < 8; i++) ts[i] = (byte) (ticks >>> (8 * i));
        return ts;
    }

    private static byte[] unicode(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_16LE);
    }

    private static byte[] oem(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] fixed(byte[] src, int len) {
        byte[] out = new byte[len];
        if (src != null) System.arraycopy(src, 0, out, 0, Math.min(src.length, len));
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean startsWith(byte[] msg, byte[] prefix) {
        if (msg.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (msg[i] != prefix[i]) return false;
        return true;
    }

    private static void writeLE16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeLE32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeLE32(byte[] dst, int off, int v) {
        dst[off] = (byte) v;
        dst[off + 1] = (byte) (v >>> 8);
        dst[off + 2] = (byte) (v >>> 16);
        dst[off + 3] = (byte) (v >>> 24);
    }

    private static int readLE16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readLE32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
