package com.nexuslink.security.cert;

import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Exports certificates (and optionally their private key) in the common interchange formats:
 * raw DER, a password-protected PKCS#12 keystore, and a concatenated PEM bundle. Pure and
 * side-effect free — callers write the returned bytes/text to disk — so every format round-trips
 * under unit test via {@link CertificateImporter} / {@link CertificateParser}.
 */
public final class CertificateExporter {

    private CertificateExporter() {}

    /** Raw DER encoding of a single certificate. */
    public static byte[] toDer(X509Certificate certificate) throws Exception {
        return certificate.getEncoded();
    }

    /**
     * A password-protected PKCS#12 keystore holding {@code privateKey} under {@code alias} with its
     * certificate {@code chain} (leaf first). Use {@link #toPkcs12TrustStore} for cert-only stores.
     */
    public static byte[] toPkcs12(String alias, PrivateKey privateKey, char[] password,
                                  List<X509Certificate> chain) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, privateKey, password, chain.toArray(new Certificate[0]));
        return store(ks, password);
    }

    /** A PKCS#12 trust store of certificate-only entries ({@code alias-0}, {@code alias-1}, …). */
    public static byte[] toPkcs12TrustStore(String aliasPrefix, char[] password,
                                            List<X509Certificate> certificates) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        for (int i = 0; i < certificates.size(); i++) {
            ks.setCertificateEntry(aliasPrefix + "-" + i, certificates.get(i));
        }
        return store(ks, password);
    }

    /** A concatenated PEM bundle (e.g. a certificate chain), leaf first. */
    public static String pemBundle(List<X509Certificate> certificates) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (X509Certificate c : certificates) sb.append(CertificateGenerator.toPem(c));
        return sb.toString();
    }

    private static byte[] store(KeyStore ks, char[] password) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, password);
        return out.toByteArray();
    }
}
