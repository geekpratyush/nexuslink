package com.nexuslink.security.cert;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Generates self-signed X.509 certificates (RSA or ECDSA) with a configurable validity window
 * and Subject Alternative Names, using BouncyCastle. The result carries the certificate and its
 * private key; {@link #toPem} renders either as PEM for export.
 */
public final class CertificateGenerator {

    private CertificateGenerator() {}

    /** Key algorithm choices exposed in the UI. */
    public enum KeyType { RSA_2048, RSA_4096, EC_P256, EC_P384 }

    /** A freshly generated self-signed certificate plus its private key. */
    public record GeneratedCertificate(X509Certificate certificate, PrivateKey privateKey) {}

    /** A PKCS#10 certificate-signing request (PEM) plus the key pair it was generated for. */
    public record CsrResult(String csrPem, PrivateKey privateKey, PublicKey publicKey) {}

    /**
     * Builds a self-signed certificate.
     *
     * @param commonName  the subject/issuer CN (e.g. {@code "localhost"})
     * @param org         organization (O), may be blank
     * @param keyType     key algorithm + size
     * @param validity    how long the certificate is valid from now
     * @param sans        Subject Alternative Names — DNS names, or {@code IP:1.2.3.4} for IPs
     */
    public static GeneratedCertificate generate(String commonName, String org, KeyType keyType,
                                                Duration validity, List<String> sans) throws Exception {
        KeyPair keyPair = newKeyPair(keyType);

        StringBuilder dn = new StringBuilder("CN=").append(commonName);
        if (org != null && !org.isBlank()) dn.append(", O=").append(org);
        X500Name name = new X500Name(dn.toString());

        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(Duration.ofMinutes(5))); // small clock-skew allowance
        Date notAfter = Date.from(now.plus(validity));
        BigInteger serial = new BigInteger(64, new SecureRandom());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, notBefore, notAfter, name, keyPair.getPublic());

        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        if (sans != null && !sans.isEmpty()) {
            GeneralName[] names = sans.stream().map(CertificateGenerator::toGeneralName).toArray(GeneralName[]::new);
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
        }

        String sigAlg = keyType.name().startsWith("RSA") ? "SHA256withRSA" : "SHA256withECDSA";
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).build(keyPair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
        cert.verify(keyPair.getPublic()); // sanity-check the self-signature
        return new GeneratedCertificate(cert, keyPair.getPrivate());
    }

    /**
     * Generates a fresh key pair and a PKCS#10 certificate-signing request (CSR) for it — what you
     * send to a CA to be issued a certificate. SANs, when given, go into a requested-extensions
     * attribute. Returns the CSR as PEM ({@code -----BEGIN CERTIFICATE REQUEST-----}) plus the keys.
     */
    public static CsrResult generateCsr(String commonName, String org, KeyType keyType,
                                        List<String> sans) throws Exception {
        KeyPair keyPair = newKeyPair(keyType);

        StringBuilder dn = new StringBuilder("CN=").append(commonName);
        if (org != null && !org.isBlank()) dn.append(", O=").append(org);
        X500Name subject = new X500Name(dn.toString());

        JcaPKCS10CertificationRequestBuilder builder =
                new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
        if (sans != null && !sans.isEmpty()) {
            GeneralName[] names = sans.stream().map(CertificateGenerator::toGeneralName).toArray(GeneralName[]::new);
            ExtensionsGenerator extensions = new ExtensionsGenerator();
            extensions.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
            builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate());
        }

        String sigAlg = keyType.name().startsWith("RSA") ? "SHA256withRSA" : "SHA256withECDSA";
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).build(keyPair.getPrivate());
        PKCS10CertificationRequest csr = builder.build(signer);
        return new CsrResult(toPem(csr), keyPair.getPrivate(), keyPair.getPublic());
    }

    private static GeneralName toGeneralName(String san) {
        if (san.regionMatches(true, 0, "IP:", 0, 3)) {
            return new GeneralName(GeneralName.iPAddress, san.substring(3).trim());
        }
        String dns = san.regionMatches(true, 0, "DNS:", 0, 4) ? san.substring(4).trim() : san.trim();
        return new GeneralName(GeneralName.dNSName, dns);
    }

    private static KeyPair newKeyPair(KeyType keyType) throws Exception {
        switch (keyType) {
            case RSA_2048, RSA_4096 -> {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(keyType == KeyType.RSA_4096 ? 4096 : 2048);
                return kpg.generateKeyPair();
            }
            case EC_P256, EC_P384 -> {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec(keyType == KeyType.EC_P384 ? "secp384r1" : "secp256r1"));
                return kpg.generateKeyPair();
            }
            default -> throw new IllegalArgumentException("Unsupported key type: " + keyType);
        }
    }

    /** Renders any PEM-able object (certificate or private key) as a PEM string. */
    public static String toPem(Object pemObject) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(pemObject);
        }
        return sw.toString();
    }
}
