package com.nexuslink.security.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Builds an {@link SSLContext} from a {@link TlsConfig}: a key manager from the client key store
 * (for mutual TLS), and a trust manager from the trust store (the CAs to trust) — or a trust-all
 * manager, or the JDK default. The resulting context can drive any TLS client (the JDK
 * {@code HttpClient}, sockets, drivers that accept an {@code SSLContext}/{@code SSLSocketFactory}).
 *
 * <p>Pure with respect to inputs (it only reads the configured key/trust store files); fully
 * exercised by a loopback TLS + mTLS handshake test, so no live server is needed.
 */
public final class TlsContextFactory {

    private TlsContextFactory() {}

    /** Builds an {@link SSLContext} for {@code config}. Returns the JDK default when nothing is set. */
    public static SSLContext create(TlsConfig config) throws Exception {
        if (config == null || !config.isCustom()) return SSLContext.getDefault();

        KeyManager[] keyManagers = null;
        if (config.hasKeyStore()) {
            KeyStore ks = load(config.keyStorePath(), typeFor(config.keyStoreType(), config.keyStorePath()),
                    config.keyStorePassword());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, config.keyStorePassword());
            keyManagers = kmf.getKeyManagers();
        }

        TrustManager[] trustManagers = null;
        if (config.trustAll()) {
            trustManagers = new TrustManager[]{TRUST_ALL};
        } else if (config.hasTrustStore()) {
            KeyStore ts = load(config.trustStorePath(), typeFor(config.trustStoreType(), config.trustStorePath()),
                    config.trustStorePassword());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            trustManagers = tmf.getTrustManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, new SecureRandom());
        return context;
    }

    /** Resolves a keystore type: the explicit value when given, else {@code .jks} → JKS / else PKCS12. */
    public static String typeFor(String explicitType, String path) {
        if (explicitType != null && !explicitType.isBlank()) return explicitType.trim().toUpperCase();
        return path != null && path.toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
    }

    private static KeyStore load(String path, String type, char[] password) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            ks.load(in, password);
        }
        return ks;
    }

    /** Accepts any certificate — for connecting to self-signed / untrusted test servers only. */
    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
