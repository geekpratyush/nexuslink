package com.nexuslink.security.cert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CertificateManagerTest {

    @Test
    void generatesParseableSelfSignedRsaCert() throws Exception {
        var gen = CertificateGenerator.generate("localhost", "NexusLink",
                CertificateGenerator.KeyType.RSA_2048, Duration.ofDays(365),
                List.of("localhost", "DNS:example.test", "IP:127.0.0.1"));

        CertificateInfo info = CertificateParser.toInfo(gen.certificate());
        assertEquals("localhost", info.commonName());
        assertTrue(info.selfSigned(), "self-signed cert has matching subject/issuer");
        assertEquals("RSA", info.keyAlgorithm());
        assertEquals(2048, info.keySize());
        assertTrue(info.signatureAlgorithm().toUpperCase().contains("RSA"));
        assertEquals(CertificateInfo.Status.VALID, info.status());
        // SANs survive the round-trip (DNS names + the IP).
        assertTrue(info.subjectAltNames().stream().anyMatch(s -> s.contains("example.test")));
        assertTrue(info.subjectAltNames().stream().anyMatch(s -> s.contains("127.0.0.1")));
    }

    @Test
    void generatesEcCertWithExpectedCurveSize() throws Exception {
        var gen = CertificateGenerator.generate("ec.example", "",
                CertificateGenerator.KeyType.EC_P256, Duration.ofDays(30), List.of());
        CertificateInfo info = CertificateParser.toInfo(gen.certificate());
        assertEquals("EC", info.keyAlgorithm());
        assertEquals(256, info.keySize());
    }

    @Test
    void statusReflectsValidityWindow() throws Exception {
        var gen = CertificateGenerator.generate("soon.example", "",
                CertificateGenerator.KeyType.EC_P256, Duration.ofDays(10), List.of());
        CertificateInfo info = CertificateParser.toInfo(gen.certificate());
        // 10-day cert is inside the 30-day expiring window.
        assertEquals(CertificateInfo.Status.EXPIRING_SOON, info.status());
        // Far future → already expired; far past → not yet valid.
        assertEquals(CertificateInfo.Status.EXPIRED, info.statusAt(Instant.now().plus(Duration.ofDays(40))));
        assertEquals(CertificateInfo.Status.NOT_YET_VALID, info.statusAt(Instant.now().minus(Duration.ofDays(1))));
    }

    @Test
    void pemRoundTripsThroughParser() throws Exception {
        var gen = CertificateGenerator.generate("pem.example", "",
                CertificateGenerator.KeyType.RSA_2048, Duration.ofDays(90), List.of());
        String pem = CertificateGenerator.toPem(gen.certificate());
        assertTrue(pem.contains("BEGIN CERTIFICATE"));
        X509Certificate reloaded = CertificateParser.load(pem.getBytes());
        assertEquals(gen.certificate(), reloaded);
    }

    @Test
    void storePersistsAndReloadsKeyPairAndTrustedCert(@TempDir Path dir) throws Exception {
        char[] pw = "changeit".toCharArray();
        Path file = dir.resolve("certs.p12");

        var leaf = CertificateGenerator.generate("leaf.example", "",
                CertificateGenerator.KeyType.RSA_2048, Duration.ofDays(365), List.of());
        var trusted = CertificateGenerator.generate("ca.example", "",
                CertificateGenerator.KeyType.EC_P256, Duration.ofDays(365), List.of());

        CertificateStore store = CertificateStore.createEmpty("PKCS12", pw);
        store.importKeyPair("leaf", leaf.privateKey(), leaf.certificate());
        store.importCertificate("trusted-ca", trusted.certificate());
        store.save(file);

        CertificateStore reopened = CertificateStore.load(file, "PKCS12", pw);
        assertEquals(List.of("leaf", "trusted-ca"), reopened.aliases());
        assertTrue(reopened.hasKey("leaf"));
        assertFalse(reopened.hasKey("trusted-ca"));
        assertEquals("leaf.example", reopened.info("leaf").commonName());

        reopened.delete("trusted-ca");
        assertEquals(1, reopened.size());
    }
}
