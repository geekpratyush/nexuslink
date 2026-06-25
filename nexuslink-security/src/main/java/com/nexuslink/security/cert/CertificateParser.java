package com.nexuslink.security.cert;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Decodes X.509 certificates (PEM or DER) into {@link CertificateInfo} using only the JDK's
 * {@link CertificateFactory}. Extracts subject, issuer, validity window, SANs, key/signature
 * algorithms, the CA flag, and a SHA-256 fingerprint.
 */
public final class CertificateParser {

    private CertificateParser() {}

    /** Parses the first certificate found in {@code data} (PEM text or DER bytes). */
    public static CertificateInfo parse(byte[] data) throws Exception {
        return toInfo(load(data));
    }

    /** Loads the X.509 certificate from PEM or DER bytes (the JDK factory accepts both). */
    public static X509Certificate load(byte[] data) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data));
    }

    /** Maps a live {@link X509Certificate} to the UI snapshot. */
    public static CertificateInfo toInfo(X509Certificate c) throws Exception {
        boolean selfSigned = c.getSubjectX500Principal().equals(c.getIssuerX500Principal());
        // basicConstraints >= 0 means the cert is a CA; -1 means it is not.
        boolean ca = c.getBasicConstraints() != -1;

        return new CertificateInfo(
                c.getSubjectX500Principal().getName(),
                c.getIssuerX500Principal().getName(),
                c.getSerialNumber().toString(16),
                c.getNotBefore().toInstant(),
                c.getNotAfter().toInstant(),
                subjectAltNames(c),
                c.getPublicKey().getAlgorithm(),
                keySize(c),
                c.getSigAlgName(),
                selfSigned,
                ca,
                sha256(c.getEncoded()));
    }

    private static List<String> subjectAltNames(X509Certificate c) throws Exception {
        Collection<List<?>> sans = c.getSubjectAlternativeNames();
        List<String> out = new ArrayList<>();
        if (sans == null) return out;
        for (List<?> entry : sans) {
            // entry = [Integer type, value]; 2=DNS, 7=IP — show the value with a type hint.
            Object type = entry.get(0);
            Object value = entry.get(1);
            String prefix = "2".equals(String.valueOf(type)) ? "DNS:"
                    : "7".equals(String.valueOf(type)) ? "IP:" : "";
            out.add(prefix + value);
        }
        return out;
    }

    private static int keySize(X509Certificate c) {
        return switch (c.getPublicKey()) {
            case RSAPublicKey rsa -> rsa.getModulus().bitLength();
            case ECPublicKey ec -> ec.getParams().getCurve().getField().getFieldSize();
            default -> 0;
        };
    }

    private static String sha256(byte[] der) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder(hash.length * 3);
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", hash[i]));
        }
        return sb.toString();
    }
}
