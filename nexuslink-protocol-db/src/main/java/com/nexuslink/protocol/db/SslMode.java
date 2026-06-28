package com.nexuslink.protocol.db;

/**
 * A database-agnostic TLS verification level. Each JDBC driver spells these out differently
 * (PostgreSQL {@code sslmode}, MySQL {@code sslMode}, MariaDB {@code sslMode}, SQL Server
 * {@code encrypt}/{@code trustServerCertificate}); {@link JdbcTlsParams} translates the generic
 * value into the right driver-specific connection properties.
 */
public enum SslMode {

    /** Use whatever the driver/URL already specifies — emit no TLS properties. */
    DEFAULT,

    /** No TLS at all (plaintext). */
    DISABLE,

    /** Encrypt the connection but do not verify the server certificate or hostname. */
    REQUIRE,

    /** Encrypt and verify the certificate chain against the trusted CAs (no hostname check). */
    VERIFY_CA,

    /** Encrypt and verify both the certificate chain and the server hostname (strongest). */
    VERIFY_FULL;

    /** True when TLS should be turned on (anything stronger than {@link #DISABLE}/{@link #DEFAULT}). */
    public boolean enablesTls() {
        return this == REQUIRE || this == VERIFY_CA || this == VERIFY_FULL;
    }

    /** True when the server certificate must be validated against a trust anchor. */
    public boolean verifiesCertificate() {
        return this == VERIFY_CA || this == VERIFY_FULL;
    }
}
