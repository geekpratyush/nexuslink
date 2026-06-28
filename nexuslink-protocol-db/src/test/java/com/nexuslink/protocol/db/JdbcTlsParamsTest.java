package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the driver-agnostic {@link JdbcTlsSpec} is mapped to the correct driver-specific TLS
 * connection properties for each supported database. Pure string mapping — no live DB or files.
 */
class JdbcTlsParamsTest {

    private static JdbcTlsSpec mode(SslMode mode) {
        return new JdbcTlsSpec(mode, false, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void emptySpecAndUnknownDriversProduceNoProperties() {
        assertTrue(JdbcTlsParams.forDriver("postgresql", JdbcTlsSpec.none()).isEmpty());
        assertTrue(JdbcTlsParams.forDriver("postgresql", null).isEmpty());
        // Embedded DBs need no network TLS.
        assertTrue(JdbcTlsParams.forDriver("sqlite", mode(SslMode.VERIFY_FULL)).isEmpty());
        assertTrue(JdbcTlsParams.forDriver("h2", mode(SslMode.VERIFY_FULL)).isEmpty());
        // Unknown driver id → no mapping.
        assertTrue(JdbcTlsParams.forDriver("oracle", mode(SslMode.REQUIRE)).isEmpty());
    }

    @Test
    void postgresVerifyFullWithCaAndClientCert() {
        JdbcTlsSpec spec = new JdbcTlsSpec(SslMode.VERIFY_FULL, false,
                "/certs/ca.pem", "/certs/client.crt", "/certs/client.key",
                null, null, null, null, null, null);
        Map<String, String> p = JdbcTlsParams.forDriver("postgresql", spec);
        assertEquals("true", p.get("ssl"));
        assertEquals("verify-full", p.get("sslmode"));
        assertEquals("/certs/ca.pem", p.get("sslrootcert"));
        assertEquals("/certs/client.crt", p.get("sslcert"));
        assertEquals("/certs/client.key", p.get("sslkey"));
    }

    @Test
    void postgresTrustAllUsesNonValidatingFactory() {
        JdbcTlsSpec spec = new JdbcTlsSpec(SslMode.REQUIRE, true, null, null, null,
                null, null, null, null, null, null);
        Map<String, String> p = JdbcTlsParams.forDriver("postgresql", spec);
        assertEquals("require", p.get("sslmode"));
        assertEquals("org.postgresql.ssl.NonValidatingFactory", p.get("sslfactory"));
    }

    @Test
    void postgresDisableTurnsTlsOff() {
        Map<String, String> p = JdbcTlsParams.forDriver("postgresql", mode(SslMode.DISABLE));
        assertEquals("disable", p.get("sslmode"));
        assertFalse(p.containsKey("ssl"));
    }

    @Test
    void cockroachReusesPostgresMapping() {
        Map<String, String> p = JdbcTlsParams.forDriver("cockroachdb", mode(SslMode.VERIFY_CA));
        assertEquals("verify-ca", p.get("sslmode"));
        assertEquals("true", p.get("ssl"));
    }

    @Test
    void mysqlMapsVerifyFullToVerifyIdentityAndKeyStores() {
        JdbcTlsSpec spec = new JdbcTlsSpec(SslMode.VERIFY_FULL, false, null, null, null,
                "/stores/truststore.p12", "trustpw", null,
                "/stores/client.jks", "keypw", null);
        Map<String, String> p = JdbcTlsParams.forDriver("mysql", spec);
        assertEquals("VERIFY_IDENTITY", p.get("sslMode"));
        assertTrue(p.get("trustCertificateKeyStoreUrl").startsWith("file:"));
        assertTrue(p.get("trustCertificateKeyStoreUrl").endsWith("truststore.p12"));
        assertEquals("trustpw", p.get("trustCertificateKeyStorePassword"));
        assertEquals("PKCS12", p.get("trustCertificateKeyStoreType"));
        assertEquals("keypw", p.get("clientCertificateKeyStorePassword"));
        assertEquals("JKS", p.get("clientCertificateKeyStoreType"));
    }

    @Test
    void mysqlTrustAllRequiresButDoesNotVerify() {
        Map<String, String> p = JdbcTlsParams.forDriver("mysql",
                new JdbcTlsSpec(SslMode.VERIFY_FULL, true, null, null, null,
                        null, null, null, null, null, null));
        assertEquals("REQUIRED", p.get("sslMode"));
    }

    @Test
    void mariadbUsesPemServerCertAndStores() {
        JdbcTlsSpec spec = new JdbcTlsSpec(SslMode.VERIFY_FULL, false,
                "/certs/ca.pem", null, null,
                null, null, null, "/stores/client.p12", "kp", null);
        Map<String, String> p = JdbcTlsParams.forDriver("mariadb", spec);
        assertEquals("verify-full", p.get("sslMode"));
        assertEquals("/certs/ca.pem", p.get("serverSslCert"));
        assertEquals("/stores/client.p12", p.get("keyStore"));
        assertEquals("kp", p.get("keyStorePassword"));
        assertEquals("PKCS12", p.get("keyStoreType"));
    }

    @Test
    void mariadbTrustAllUsesTrustMode() {
        Map<String, String> p = JdbcTlsParams.forDriver("mariadb",
                new JdbcTlsSpec(SslMode.REQUIRE, true, null, null, null,
                        null, null, null, null, null, null));
        assertEquals("trust", p.get("sslMode"));
    }

    @Test
    void sqlServerEncryptAndTrustServerCertificate() {
        Map<String, String> verify = JdbcTlsParams.forDriver("sqlserver", mode(SslMode.VERIFY_FULL));
        assertEquals("true", verify.get("encrypt"));
        assertEquals("false", verify.get("trustServerCertificate"));

        Map<String, String> trustAll = JdbcTlsParams.forDriver("sqlserver",
                new JdbcTlsSpec(SslMode.REQUIRE, true, null, null, null,
                        null, null, null, null, null, null));
        assertEquals("true", trustAll.get("encrypt"));
        assertEquals("true", trustAll.get("trustServerCertificate"));

        Map<String, String> off = JdbcTlsParams.forDriver("sqlserver", mode(SslMode.DISABLE));
        assertEquals("false", off.get("encrypt"));
    }

    @Test
    void jksExtensionMapsToJksType() {
        assertEquals("JKS", JdbcTlsParams.storeType(null, "/x/store.jks"));
        assertEquals("PKCS12", JdbcTlsParams.storeType(null, "/x/store.p12"));
        assertEquals("PKCS12", JdbcTlsParams.storeType(null, "/x/store.pfx"));
        assertEquals("BCFKS", JdbcTlsParams.storeType("bcfks", "/x/store.jks"));
    }
}
