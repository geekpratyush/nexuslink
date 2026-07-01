package com.nexuslink.security.cert;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PemParser} entirely offline: PEM strings are built from base64 of small,
 * self-chosen byte arrays so no external key or certificate files are needed. Covers single and
 * concatenated blocks, private keys, explanatory text, CRLF endings, type classification, the
 * quick {@code isPem} check, and the malformed-input error paths.
 */
class PemParserTest {

    /** Wraps arbitrary DER bytes as a PEM block using LF line endings and 64-char base64 lines. */
    private static String pem(String label, byte[] der) {
        return wrap(label, der, "\n");
    }

    private static String wrap(String label, byte[] der, String eol) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(label).append("-----").append(eol);
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append(eol);
        }
        sb.append("-----END ").append(label).append("-----").append(eol);
        return sb.toString();
    }

    private static byte[] bytes(int len, int seed) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (seed + i * 7);
        }
        return b;
    }

    @Test
    void singleCertificateBlockDecodesToExpectedDerLength() {
        byte[] der = bytes(200, 1);
        List<PemBlock> blocks = PemParser.parseAll(pem("CERTIFICATE", der));
        assertEquals(1, blocks.size());
        PemBlock block = blocks.get(0);
        assertEquals("CERTIFICATE", block.label());
        assertEquals(200, block.length());
        assertArrayEquals(der, block.content());
        assertEquals(PemBlock.Type.CERTIFICATE, block.type());
    }

    @Test
    void twoConcatenatedBlocksAreBothParsed() {
        byte[] leaf = bytes(90, 3);
        byte[] issuer = bytes(140, 9);
        String chain = pem("CERTIFICATE", leaf) + pem("CERTIFICATE", issuer);
        List<PemBlock> blocks = PemParser.parseAll(chain);
        assertEquals(2, blocks.size());
        assertArrayEquals(leaf, blocks.get(0).content());
        assertArrayEquals(issuer, blocks.get(1).content());
    }

    @Test
    void privateKeyBlockParsesAndClassifies() {
        byte[] der = bytes(120, 5);
        PemBlock block = PemParser.first(pem("PRIVATE KEY", der), "PRIVATE KEY");
        assertNotNull(block);
        assertArrayEquals(der, block.content());
        assertEquals(PemBlock.Type.PRIVATE_KEY, block.type());
        assertEquals(PemBlock.Type.PRIVATE_KEY,
                PemParser.first(pem("RSA PRIVATE KEY", der), "RSA PRIVATE KEY").type());
        assertEquals(PemBlock.Type.PRIVATE_KEY,
                PemParser.first(pem("EC PRIVATE KEY", der), "EC PRIVATE KEY").type());
    }

    @Test
    void leadingAndTrailingExplanatoryTextIsIgnored() {
        byte[] der = bytes(64, 2);
        String text = "Subject: CN=example\nComment: not part of the block\n\n"
                + pem("CERTIFICATE", der)
                + "\nThis trailing note is also ignored.\n";
        List<PemBlock> blocks = PemParser.parseAll(text);
        assertEquals(1, blocks.size());
        assertArrayEquals(der, blocks.get(0).content());
    }

    @Test
    void interBlockCommentsAreIgnored() {
        byte[] a = bytes(48, 4);
        byte[] b = bytes(48, 8);
        String text = pem("CERTIFICATE", a)
                + "# a comment between blocks\nbag attributes junk\n"
                + pem("CERTIFICATE", b);
        List<PemBlock> blocks = PemParser.parseAll(text);
        assertEquals(2, blocks.size());
        assertArrayEquals(a, blocks.get(0).content());
        assertArrayEquals(b, blocks.get(1).content());
    }

    @Test
    void crlfLineEndingsAreHandled() {
        byte[] der = bytes(150, 6);
        String text = wrap("CERTIFICATE", der, "\r\n");
        List<PemBlock> blocks = PemParser.parseAll(text);
        assertEquals(1, blocks.size());
        assertArrayEquals(der, blocks.get(0).content());
    }

    @Test
    void typeClassificationCoversAllCategories() {
        byte[] der = bytes(16, 1);
        assertEquals(PemBlock.Type.CERTIFICATE,
                PemParser.parseAll(pem("CERTIFICATE", der)).get(0).type());
        assertEquals(PemBlock.Type.PUBLIC_KEY,
                PemParser.parseAll(pem("PUBLIC KEY", der)).get(0).type());
        assertEquals(PemBlock.Type.CRL,
                PemParser.parseAll(pem("X509 CRL", der)).get(0).type());
        assertEquals(PemBlock.Type.CSR,
                PemParser.parseAll(pem("CERTIFICATE REQUEST", der)).get(0).type());
        assertEquals(PemBlock.Type.OTHER,
                PemParser.parseAll(pem("DH PARAMETERS", der)).get(0).type());
    }

    @Test
    void firstReturnsMatchingLabelOrNull() {
        byte[] cert = bytes(30, 1);
        byte[] key = bytes(30, 2);
        String text = pem("CERTIFICATE", cert) + pem("PRIVATE KEY", key);
        assertArrayEquals(key, PemParser.first(text, "PRIVATE KEY").content());
        assertArrayEquals(cert, PemParser.first(text, "CERTIFICATE").content());
        assertNull(PemParser.first(text, "PUBLIC KEY"));
        assertNull(PemParser.first(text, null));
    }

    @Test
    void mismatchedBeginEndLabelsThrow() {
        String text = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getEncoder().encodeToString(bytes(20, 1)) + "\n"
                + "-----END PRIVATE KEY-----\n";
        PemParser.PemException ex =
                assertThrows(PemParser.PemException.class, () -> PemParser.parseAll(text));
        assertTrue(ex.getMessage().contains("mismatch"));
    }

    @Test
    void truncatedBlockWithoutEndThrows() {
        String text = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getEncoder().encodeToString(bytes(20, 1)) + "\n";
        PemParser.PemException ex =
                assertThrows(PemParser.PemException.class, () -> PemParser.parseAll(text));
        assertTrue(ex.getMessage().contains("Truncated") || ex.getMessage().contains("missing END"));
    }

    @Test
    void invalidBase64Throws() {
        String text = "-----BEGIN CERTIFICATE-----\n"
                + "not*valid*base64*@@@\n"
                + "-----END CERTIFICATE-----\n";
        PemParser.PemException ex =
                assertThrows(PemParser.PemException.class, () -> PemParser.parseAll(text));
        assertTrue(ex.getMessage().contains("base64"));
    }

    @Test
    void isPemDistinguishesPemFromPlainText() {
        assertTrue(PemParser.isPem(pem("CERTIFICATE", bytes(10, 1))));
        assertTrue(PemParser.isPem("prefix text\n-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"));
        assertFalse(PemParser.isPem("just some plain text, no armour"));
        assertFalse(PemParser.isPem(""));
        assertFalse(PemParser.isPem(null));
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertTrue(PemParser.parseAll("").isEmpty());
        assertTrue(PemParser.parseAll(null).isEmpty());
        assertTrue(PemParser.parseAll("no pem markers here at all").isEmpty());
    }

    @Test
    void roundTripThroughUtf8Bytes() {
        byte[] der = "hello DER payload".getBytes(StandardCharsets.UTF_8);
        String text = pem("CERTIFICATE", der);
        assertArrayEquals(der, PemParser.parseAll(text).get(0).content());
    }
}
