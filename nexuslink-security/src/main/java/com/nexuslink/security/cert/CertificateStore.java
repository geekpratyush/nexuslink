package com.nexuslink.security.cert;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A keystore-backed certificate store (PKCS12 by default, JKS supported). Wraps a JCA
 * {@link KeyStore} with simple list / import / export / delete operations and disk persistence,
 * so the certificate manager can keep a user's trusted certs and self-signed key pairs in one
 * password-protected file.
 */
public final class CertificateStore {

    private final KeyStore keyStore;
    private final char[] password;

    private CertificateStore(KeyStore keyStore, char[] password) {
        this.keyStore = keyStore;
        this.password = password;
    }

    /** Creates an empty in-memory PKCS12 store. */
    public static CertificateStore createEmpty(String type, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        ks.load(null, password);
        return new CertificateStore(ks, password);
    }

    /** Loads a store from disk; creates an empty one if the file does not exist. */
    public static CertificateStore load(Path file, String type, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                ks.load(in, password);
            }
        } else {
            ks.load(null, password);
        }
        return new CertificateStore(ks, password);
    }

    public void save(Path file) throws Exception {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, password);
        }
    }

    /** Stores a CA/leaf certificate as a trusted entry under {@code alias}. */
    public void importCertificate(String alias, X509Certificate cert) throws Exception {
        keyStore.setCertificateEntry(alias, cert);
    }

    /** Stores a private key plus its certificate chain under {@code alias}. */
    public void importKeyPair(String alias, PrivateKey key, X509Certificate... chain) throws Exception {
        keyStore.setKeyEntry(alias, key, password, chain);
    }

    public List<String> aliases() throws Exception {
        List<String> list = new ArrayList<>(Collections.list(keyStore.aliases()));
        Collections.sort(list);
        return list;
    }

    public X509Certificate getCertificate(String alias) throws Exception {
        Certificate c = keyStore.getCertificate(alias);
        return (c instanceof X509Certificate x) ? x : null;
    }

    /** Returns the parsed view of the certificate stored under {@code alias}. */
    public CertificateInfo info(String alias) throws Exception {
        X509Certificate c = getCertificate(alias);
        return c == null ? null : CertificateParser.toInfo(c);
    }

    public boolean hasKey(String alias) throws Exception {
        return keyStore.isKeyEntry(alias);
    }

    /** Returns the private key under {@code alias}, or {@code null} if it has no key entry. */
    public PrivateKey getKey(String alias) throws Exception {
        if (!keyStore.isKeyEntry(alias)) return null;
        return (PrivateKey) keyStore.getKey(alias, password);
    }

    /** Returns the X.509 certificate chain under {@code alias}. */
    public X509Certificate[] getChain(String alias) throws Exception {
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null) {
            X509Certificate c = getCertificate(alias);
            return c == null ? new X509Certificate[0] : new X509Certificate[]{c};
        }
        X509Certificate[] out = new X509Certificate[chain.length];
        for (int i = 0; i < chain.length; i++) out[i] = (X509Certificate) chain[i];
        return out;
    }

    public void delete(String alias) throws Exception {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
    }

    public int size() throws Exception {
        return keyStore.size();
    }
}
