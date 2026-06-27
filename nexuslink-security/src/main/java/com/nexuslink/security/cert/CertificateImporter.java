package com.nexuslink.security.cert;

import java.io.ByteArrayInputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports certificates and private keys from PKCS#12 ({@code .p12}/{@code .pfx}) or JKS keystore
 * bundles. Decodes every alias into a {@link Entry} (the leaf certificate, its chain, and the
 * private key when present), so a bundle can be browsed and its entries added to the trust store.
 * Uses only the JDK {@link KeyStore} API; round-trips against {@link CertificateExporter} under test.
 */
public final class CertificateImporter {

    private CertificateImporter() {}

    /** One keystore alias: its leaf certificate, full chain, and private key (null for cert-only). */
    public record Entry(String alias, X509Certificate certificate,
                        List<X509Certificate> chain, PrivateKey privateKey) {
        public boolean hasPrivateKey() { return privateKey != null; }
    }

    /** Loads every entry from a keystore bundle. {@code type} is {@code "PKCS12"} or {@code "JKS"}. */
    public static List<Entry> load(byte[] bundle, String type, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        ks.load(new ByteArrayInputStream(bundle), password);

        List<Entry> entries = new ArrayList<>();
        for (Enumeration<String> aliases = ks.aliases(); aliases.hasMoreElements(); ) {
            String alias = aliases.nextElement();

            List<X509Certificate> chain = new ArrayList<>();
            Certificate[] raw = ks.getCertificateChain(alias);
            if (raw != null) {
                for (Certificate c : raw) if (c instanceof X509Certificate x) chain.add(x);
            } else if (ks.getCertificate(alias) instanceof X509Certificate x) {
                chain.add(x);
            }

            PrivateKey key = null;
            if (ks.isKeyEntry(alias)) {
                Key k = ks.getKey(alias, password);
                if (k instanceof PrivateKey pk) key = pk;
            }
            X509Certificate leaf = chain.isEmpty() ? null : chain.get(0);
            entries.add(new Entry(alias, leaf, chain, key));
        }
        return entries;
    }

    /**
     * Detects the keystore type from a file name extension ({@code .jks} → JKS, otherwise PKCS12,
     * which covers {@code .p12}, {@code .pfx}, and the common default).
     */
    public static String typeForFileName(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        return lower.endsWith(".jks") ? "JKS" : "PKCS12";
    }

    /** Indexes loaded entries by alias for quick lookup. */
    public static Map<String, Entry> byAlias(List<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : entries) map.put(e.alias(), e);
        return map;
    }
}
