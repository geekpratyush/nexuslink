package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the driver catalog and that bundled drivers are actually available on the
 * classpath while on-demand ones are not (until loaded).
 */
class JdbcDriverRegistryTest {

    @Test
    void catalogContainsBundledAndOnDemandDrivers() {
        assertTrue(JdbcDriverRegistry.byId("postgresql").orElseThrow().bundled());
        assertTrue(JdbcDriverRegistry.byId("h2").orElseThrow().bundled());
        assertFalse(JdbcDriverRegistry.byId("oracle").orElseThrow().bundled());
        assertTrue(JdbcDriverRegistry.byId("oracle").orElseThrow().requiresLicenseAck());
    }

    @Test
    void bundledDriversAreAvailableOnDemandAreNot() {
        // Bundled: their jars are on the test classpath, so the class resolves.
        assertTrue(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("sqlite").orElseThrow()));
        assertTrue(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("h2").orElseThrow()));
        assertTrue(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("postgresql").orElseThrow()));
        assertTrue(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("mysql").orElseThrow()));
        assertTrue(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("mariadb").orElseThrow()));

        // On-demand: not bundled, so the class is not present until a jar is loaded.
        assertFalse(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("oracle").orElseThrow()));
        assertFalse(JdbcDriverRegistry.isAvailable(JdbcDriverRegistry.byId("db2").orElseThrow()));
    }

    @Test
    void redshiftAndBigQueryAreOnDemandCloudWarehouses() {
        DriverInfo redshift = JdbcDriverRegistry.byId("redshift").orElseThrow();
        assertEquals("com.amazon.redshift.jdbc.Driver", redshift.driverClass());
        assertFalse(redshift.bundled());
        assertFalse(redshift.requiresLicenseAck(), "Redshift's JDBC driver is Apache-2.0");

        DriverInfo bigquery = JdbcDriverRegistry.byId("bigquery").orElseThrow();
        assertFalse(bigquery.bundled());
        assertTrue(bigquery.requiresLicenseAck(), "Simba BigQuery driver is proprietary");
        // Neither driver jar is on the classpath, so it must report unavailable until loaded.
        assertFalse(JdbcDriverRegistry.isAvailable(redshift));
        assertFalse(JdbcDriverRegistry.isAvailable(bigquery));
    }

    @Test
    void cockroachReusesPostgresDriver() {
        DriverInfo cockroach = JdbcDriverRegistry.byId("cockroachdb").orElseThrow();
        assertEquals("org.postgresql.Driver", cockroach.driverClass());
        assertTrue(cockroach.bundled());
    }
}
