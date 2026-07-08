package com.nexuslink.protocol.db;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure config-logic tests for {@link JdbcPoolConfig} — no database involved. */
class JdbcPoolConfigTest {

    @Test
    void defaultsMatchDocumentedValues() {
        JdbcPoolConfig c = JdbcPoolConfig.defaults();
        assertEquals(JdbcPoolConfig.DEFAULT_MAX_POOL_SIZE, c.maxPoolSize());
        assertEquals(JdbcPoolConfig.DEFAULT_MIN_IDLE, c.minIdle());
        assertEquals(JdbcPoolConfig.DEFAULT_CONNECTION_TIMEOUT_MS, c.connectionTimeoutMs());
        assertEquals(JdbcPoolConfig.DEFAULT_VALIDATION_TIMEOUT_MS, c.validationTimeoutMs());
        assertTrue(c.autoCommit());
        assertFalse(c.readOnly());
    }

    @Test
    void builderOverridesAreKept() {
        JdbcPoolConfig c = JdbcPoolConfig.builder()
                .maxPoolSize(20).minIdle(5)
                .connectionTimeoutMs(1_000).validationTimeoutMs(500)
                .readOnly(true).autoCommit(false)
                .connectionTestQuery("SELECT 1")
                .build();
        assertEquals(20, c.maxPoolSize());
        assertEquals(5, c.minIdle());
        assertEquals(1_000, c.connectionTimeoutMs());
        assertEquals(500, c.validationTimeoutMs());
        assertTrue(c.readOnly());
        assertFalse(c.autoCommit());
        assertEquals("SELECT 1", c.connectionTestQuery());
    }

    @Test
    void clampsFootGuns() {
        // maxPoolSize below 1 → 1; minIdle can't exceed maxPoolSize; timeouts have floors.
        JdbcPoolConfig c = JdbcPoolConfig.builder()
                .maxPoolSize(0).minIdle(50)
                .connectionTimeoutMs(1).validationTimeoutMs(1)
                .build();
        assertEquals(1, c.maxPoolSize());
        assertEquals(1, c.minIdle(), "minIdle clamped to maxPoolSize");
        assertTrue(c.connectionTimeoutMs() >= 250);
        assertTrue(c.validationTimeoutMs() >= 250);
    }

    @Test
    void toHikariConfigMapsUrlCredentialsAndProps() {
        JdbcPoolConfig c = JdbcPoolConfig.builder().maxPoolSize(7).minIdle(3).build();
        HikariConfig hc = c.toHikariConfig("jdbc:postgresql://h/db", "alice", "secret",
                Map.of("sslmode", "require"), "pool-x");

        assertEquals("jdbc:postgresql://h/db", hc.getJdbcUrl());
        assertEquals("alice", hc.getUsername());
        assertEquals("secret", hc.getPassword());
        assertEquals("pool-x", hc.getPoolName());
        assertEquals(7, hc.getMaximumPoolSize());
        assertEquals(3, hc.getMinimumIdle());
        assertEquals("require", hc.getDataSourceProperties().getProperty("sslmode"));
        // Deliberately left unset so DriverManager (and any DriverShim) resolves the driver.
        assertNull(hc.getDriverClassName());
    }

    @Test
    void blankUserProducesNoCredentials() {
        HikariConfig hc = JdbcPoolConfig.defaults()
                .toHikariConfig("jdbc:sqlite::memory:", "  ", null, Map.of(), null);
        assertNull(hc.getUsername());
        assertNull(hc.getPassword());
    }

    @Test
    void keepaliveIgnoredWhenNotBelowMaxLifetime() {
        // keepalive >= maxLifetime is invalid for Hikari; applyTo must not set it.
        JdbcPoolConfig c = JdbcPoolConfig.builder()
                .maxLifetimeMs(10_000).keepaliveTimeMs(20_000).build();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:mem:x");
        c.applyTo(hc);
        assertEquals(0, hc.getKeepaliveTime());
    }
}
