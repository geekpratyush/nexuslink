package com.nexuslink.security.cert;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the cert export/import/CSR additions entirely offline: DER and PEM export re-parse to
 * the same certificate, a PKCS#12 keystore re-loads its key + chain, and a generated PKCS#10 CSR is
 * well-formed PEM tied to the right subject.
 */
class CertificateExportImportTest {

    private static CertificateGenerator.GeneratedCertificate newCert() throws Exception {
        return CertificateGenerator.generate("test.example.com", "Acme",
                CertificateGenerator.KeyType.EC_P256, Duration.ofDays(30), List.of("DNS:test.example.com"));
    }

    @Test
    void derExportReparsesToTheSameCertificate() throws Exception {
        X509Certificate cert = newCert().certificate();
        byte[] der = CertificateExporter.toDer(cert);
        X509Certificate reloaded = CertificateParser.load(der);
        assertArrayEquals(cert.getEncoded(), reloaded.getEncoded());
        assertEquals(cert.getSubjectX500Principal(), reloaded.getSubjectX500Principal());
    }

    @Test
    void pemBundleReparses() throws Exception {
        X509Certificate cert = newCert().certificate();
        String pem = CertificateExporter.pemBundle(List.of(cert));
        assertTrue(pem.contains("BEGIN CERTIFICATE"));
        X509Certificate reloaded = CertificateParser.load(pem.getBytes());
        assertArrayEquals(cert.getEncoded(), reloaded.getEncoded());
    }

    @Test
    void pkcs12KeystoreRoundTripsKeyAndChain() throws Exception {
        CertificateGenerator.GeneratedCertificate gen = newCert();
        char[] password = "s3cret".toCharArray();
        byte[] p12 = CertificateExporter.toPkcs12("mykey", gen.privateKey(), password, List.of(gen.certificate()));

        List<CertificateImporter.Entry> entries = CertificateImporter.load(p12, "PKCS12", password);
        assertEquals(1, entries.size());
        CertificateImporter.Entry e = entries.get(0);
        assertTrue(e.hasPrivateKey());
        assertNotNull(e.certificate());
        assertArrayEquals(gen.certificate().getEncoded(), e.certificate().getEncoded());
        assertEquals(gen.privateKey().getAlgorithm(), e.privateKey().getAlgorithm());
    }

    @Test
    void pkcs12TrustStoreHoldsCertOnlyEntries() throws Exception {
        X509Certificate cert = newCert().certificate();
        char[] password = "pw".toCharArray();
        byte[] p12 = CertificateExporter.toPkcs12TrustStore("ca", password, List.of(cert));

        List<CertificateImporter.Entry> entries = CertificateImporter.load(p12, "PKCS12", password);
        assertEquals(1, entries.size());
        assertFalse(entries.get(0).hasPrivateKey());
        assertArrayEquals(cert.getEncoded(), entries.get(0).certificate().getEncoded());
    }

    @Test
    void wrongPkcs12PasswordIsRejected() throws Exception {
        CertificateGenerator.GeneratedCertificate gen = newCert();
        byte[] p12 = CertificateExporter.toPkcs12("k", gen.privateKey(), "right".toCharArray(),
                List.of(gen.certificate()));
        assertThrows(Exception.class,
                () -> CertificateImporter.load(p12, "PKCS12", "wrong".toCharArray()));
    }

    @Test
    void typeForFileNameDetectsJksVsPkcs12() {
        assertEquals("JKS", CertificateImporter.typeForFileName("truststore.jks"));
        assertEquals("PKCS12", CertificateImporter.typeForFileName("client.p12"));
        assertEquals("PKCS12", CertificateImporter.typeForFileName("client.pfx"));
    }

    @Test
    void csrGenerationProducesWellFormedPemForTheSubject() throws Exception {
        CertificateGenerator.CsrResult csr = CertificateGenerator.generateCsr(
                "csr.example.com", "Acme", CertificateGenerator.KeyType.RSA_2048,
                List.of("DNS:csr.example.com", "DNS:www.csr.example.com"));
        assertTrue(csr.csrPem().contains("BEGIN CERTIFICATE REQUEST"));
        assertTrue(csr.csrPem().contains("END CERTIFICATE REQUEST"));
        assertEquals("RSA", csr.privateKey().getAlgorithm());
        assertNotNull(csr.publicKey());
    }
}
