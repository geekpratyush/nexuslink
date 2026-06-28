package com.nexuslink.protocol.db;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates a driver-agnostic {@link JdbcTlsSpec} into the connection properties a specific JDBC
 * driver understands. Every database spells TLS out differently, so this is where that knowledge
 * lives:
 *
 * <ul>
 *   <li><b>PostgreSQL</b> / CockroachDB — {@code ssl}, {@code sslmode}, {@code sslrootcert},
 *       {@code sslcert}, {@code sslkey}, and {@code sslfactory=NonValidatingFactory} for trust-all.</li>
 *   <li><b>MySQL</b> (Connector/J 8) — {@code sslMode}, {@code trustCertificateKeyStoreUrl},
 *       {@code clientCertificateKeyStoreUrl} (+ passwords/types).</li>
 *   <li><b>MariaDB</b> — {@code sslMode}, {@code serverSslCert} (PEM), {@code trustStore},
 *       {@code keyStore} (+ passwords).</li>
 *   <li><b>SQL Server</b> — {@code encrypt}, {@code trustServerCertificate}, {@code trustStore}.</li>
 * </ul>
 *
 * <p>The returned map is meant to be merged into the {@link java.util.Properties} passed to
 * {@link java.sql.DriverManager#getConnection(String, java.util.Properties)} — the driver reads
 * its TLS settings from there, so no URL surgery is required. The method is pure (it only inspects
 * the spec's path strings; it never opens the files), so it is fully unit-testable offline.
 */
public final class JdbcTlsParams {

    private JdbcTlsParams() {}

    /**
     * Builds the TLS connection properties for {@code driverId} (the {@link DriverInfo#id()}).
     * Returns an empty map when {@code spec} is empty or the driver needs no TLS (SQLite/H2) —
     * or when the database is unknown.
     */
    public static Map<String, String> forDriver(String driverId, JdbcTlsSpec spec) {
        Map<String, String> props = new LinkedHashMap<>();
        if (spec == null || spec.isEmpty() || driverId == null) return props;

        switch (driverId) {
            case "postgresql", "cockroachdb" -> postgres(spec, props);
            case "mysql" -> mysql(spec, props);
            case "mariadb" -> mariadb(spec, props);
            case "sqlserver" -> sqlServer(spec, props);
            // sqlite / h2 are embedded — no network TLS; other drivers fall through unmapped.
            default -> { }
        }
        return props;
    }

    // ---- PostgreSQL (and CockroachDB, same wire protocol) ----
    private static void postgres(JdbcTlsSpec spec, Map<String, String> p) {
        SslMode mode = spec.mode();
        if (mode == SslMode.DISABLE) { p.put("sslmode", "disable"); return; }

        p.put("ssl", "true");
        if (spec.trustAll()) {
            // Encrypt but skip all validation — the driver's NonValidatingFactory.
            p.put("sslmode", "require");
            p.put("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        } else {
            p.put("sslmode", switch (mode) {
                case VERIFY_CA -> "verify-ca";
                case VERIFY_FULL -> "verify-full";
                default -> "require";          // DEFAULT/REQUIRE → encrypt, default verification
            });
        }
        if (spec.hasCaCert()) p.put("sslrootcert", spec.caCertPath());
        if (spec.hasClientCert()) {
            p.put("sslcert", spec.clientCertPath());
            p.put("sslkey", spec.clientKeyPath());
        }
    }

    // ---- MySQL Connector/J 8 ----
    private static void mysql(JdbcTlsSpec spec, Map<String, String> p) {
        SslMode mode = spec.mode();
        if (mode == SslMode.DISABLE) { p.put("sslMode", "DISABLED"); return; }

        if (spec.trustAll()) {
            p.put("sslMode", "REQUIRED");      // encrypt, no CA/hostname verification
        } else {
            p.put("sslMode", switch (mode) {
                case VERIFY_CA -> "VERIFY_CA";
                case VERIFY_FULL -> "VERIFY_IDENTITY";
                default -> "REQUIRED";
            });
        }
        if (spec.hasTrustStore()) {
            p.put("trustCertificateKeyStoreUrl", fileUrl(spec.trustStorePath()));
            putIfPresent(p, "trustCertificateKeyStorePassword", spec.trustStorePassword());
            p.put("trustCertificateKeyStoreType", storeType(spec.trustStoreType(), spec.trustStorePath()));
        }
        if (spec.hasKeyStore()) {
            p.put("clientCertificateKeyStoreUrl", fileUrl(spec.keyStorePath()));
            putIfPresent(p, "clientCertificateKeyStorePassword", spec.keyStorePassword());
            p.put("clientCertificateKeyStoreType", storeType(spec.keyStoreType(), spec.keyStorePath()));
        }
    }

    // ---- MariaDB Connector/J ----
    private static void mariadb(JdbcTlsSpec spec, Map<String, String> p) {
        SslMode mode = spec.mode();
        if (mode == SslMode.DISABLE) { p.put("sslMode", "disable"); return; }

        if (spec.trustAll()) {
            p.put("sslMode", "trust");         // encrypt, trust any certificate
        } else {
            p.put("sslMode", switch (mode) {
                case VERIFY_CA -> "verify-ca";
                case VERIFY_FULL -> "verify-full";
                default -> "trust";
            });
        }
        if (spec.hasCaCert()) p.put("serverSslCert", spec.caCertPath());
        if (spec.hasTrustStore()) {
            p.put("trustStore", spec.trustStorePath());
            putIfPresent(p, "trustStorePassword", spec.trustStorePassword());
            p.put("trustStoreType", storeType(spec.trustStoreType(), spec.trustStorePath()));
        }
        if (spec.hasKeyStore()) {
            p.put("keyStore", spec.keyStorePath());
            putIfPresent(p, "keyStorePassword", spec.keyStorePassword());
            p.put("keyStoreType", storeType(spec.keyStoreType(), spec.keyStorePath()));
        }
    }

    // ---- Microsoft SQL Server ----
    private static void sqlServer(JdbcTlsSpec spec, Map<String, String> p) {
        SslMode mode = spec.mode();
        if (mode == SslMode.DISABLE) { p.put("encrypt", "false"); return; }

        p.put("encrypt", "true");
        // trustServerCertificate=true bypasses chain/hostname checks (trust-all or plain REQUIRE).
        p.put("trustServerCertificate", String.valueOf(spec.trustAll() || mode == SslMode.REQUIRE));
        if (spec.hasTrustStore()) {
            p.put("trustStore", spec.trustStorePath());
            putIfPresent(p, "trustStorePassword", spec.trustStorePassword());
        }
        // hostname verification is on by default when trustServerCertificate=false (≈ VERIFY_FULL).
    }

    private static void putIfPresent(Map<String, String> p, String key, String value) {
        if (value != null && !value.isEmpty()) p.put(key, value);
    }

    /** Resolves a keystore type: explicit value, else {@code .jks} → JKS, otherwise PKCS12. */
    static String storeType(String explicitType, String path) {
        if (explicitType != null && !explicitType.isBlank()) return explicitType.trim().toUpperCase();
        return path != null && path.toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
    }

    /** A {@code file:} URL for a keystore path (MySQL's *KeyStoreUrl props require a URL). */
    static String fileUrl(String path) {
        return Path.of(path).toUri().toString();
    }
}
