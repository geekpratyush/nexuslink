package com.nexuslink.security.tls;

/**
 * TLS / mTLS material for a connection: an optional <b>trust store</b> (the CA certificates used to
 * verify the server) and an optional <b>key store</b> (the client certificate + key presented for
 * mutual TLS). {@link #trustAll} disables verification entirely (testing only). When nothing is set,
 * {@link #isCustom()} is false and callers should use the JDK's default SSL context.
 *
 * <p>Store types may be blank to autodetect from the file extension ({@code .jks} → JKS, otherwise
 * PKCS#12). Passwords are held as {@code char[]} per JCA convention.
 */
public record TlsConfig(
        String trustStorePath, char[] trustStorePassword, String trustStoreType,
        String keyStorePath, char[] keyStorePassword, String keyStoreType,
        boolean trustAll) {

    public static TlsConfig systemDefault() {
        return new TlsConfig(null, null, null, null, null, null, false);
    }

    /** Trust every server certificate without verification — convenient for self-signed test servers. */
    public static TlsConfig trustingAll() {
        return new TlsConfig(null, null, null, null, null, null, true);
    }

    public boolean hasTrustStore() { return trustStorePath != null && !trustStorePath.isBlank(); }
    public boolean hasKeyStore() { return keyStorePath != null && !keyStorePath.isBlank(); }

    /** True when any TLS material is configured (so a custom {@code SSLContext} is needed). */
    public boolean isCustom() { return trustAll || hasTrustStore() || hasKeyStore(); }

    /** True when a client certificate is configured (i.e. mutual TLS). */
    public boolean isMutualTls() { return hasKeyStore(); }
}
