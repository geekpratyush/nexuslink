package com.nexuslink.protocol.db;

/**
 * A driver-agnostic description of the TLS material for a JDBC connection. {@link JdbcTlsParams}
 * turns this into the connection properties a specific driver understands.
 *
 * <p>Two flavours of trust/identity material are supported because drivers disagree on the format:
 * <ul>
 *   <li><b>PEM files</b> — a CA certificate ({@code caCertPath}) and a client certificate +
 *       private key ({@code clientCertPath} / {@code clientKeyPath}). PostgreSQL and MariaDB take
 *       these directly ({@code sslrootcert}/{@code sslcert}/{@code sslkey},
 *       {@code serverSslCert}/{@code keyStore}).</li>
 *   <li><b>Key stores</b> — JKS/PKCS#12 files ({@code trustStorePath} / {@code keyStorePath} with
 *       passwords). MySQL Connector/J and SQL Server take these
 *       ({@code trustCertificateKeyStoreUrl}, {@code clientCertificateKeyStoreUrl},
 *       {@code trustStore}).</li>
 * </ul>
 * A field left blank/null simply contributes nothing. {@link #isEmpty()} is true when no TLS
 * material is configured, in which case no properties are produced.
 */
public record JdbcTlsSpec(
        SslMode mode,
        boolean trustAll,
        String caCertPath,            // PEM CA bundle (PostgreSQL sslrootcert, MariaDB serverSslCert)
        String clientCertPath,        // PEM client certificate (mutual TLS)
        String clientKeyPath,         // PEM/PKCS#8 client private key (mutual TLS)
        String trustStorePath, String trustStorePassword, String trustStoreType, // JKS/PKCS12 CA store
        String keyStorePath, String keyStorePassword, String keyStoreType        // JKS/PKCS12 client store
) {

    /** A no-op spec: {@link SslMode#DEFAULT} and no material — produces no connection properties. */
    public static JdbcTlsSpec none() {
        return new JdbcTlsSpec(SslMode.DEFAULT, false, null, null, null,
                null, null, null, null, null, null);
    }

    public SslMode mode() {
        return mode == null ? SslMode.DEFAULT : mode;
    }

    /** True when nothing meaningful is set (no mode, no trust-all, no files). */
    public boolean isEmpty() {
        return mode() == SslMode.DEFAULT && !trustAll
                && blank(caCertPath) && blank(clientCertPath) && blank(clientKeyPath)
                && blank(trustStorePath) && blank(keyStorePath);
    }

    boolean hasCaCert() { return !blank(caCertPath); }
    boolean hasClientCert() { return !blank(clientCertPath) && !blank(clientKeyPath); }
    boolean hasTrustStore() { return !blank(trustStorePath); }
    boolean hasKeyStore() { return !blank(keyStorePath); }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
