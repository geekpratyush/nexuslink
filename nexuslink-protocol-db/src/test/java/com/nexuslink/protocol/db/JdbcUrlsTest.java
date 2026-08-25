package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcUrlsTest {

    private String idFor(String url) {
        return JdbcUrls.forUrl(JdbcDriverRegistry.all(), url).map(DriverInfo::id).orElse(null);
    }

    @Test
    void readsTheSubprotocolWhateverFollowsIt() {
        assertEquals("postgresql", JdbcUrls.subprotocol("jdbc:postgresql://host:5432/db").orElseThrow());
        assertEquals("oracle", JdbcUrls.subprotocol("jdbc:oracle:thin:@//host:1521/ORCL").orElseThrow());
        assertEquals("sqlserver", JdbcUrls.subprotocol("jdbc:sqlserver://host;databaseName=x").orElseThrow());
        assertEquals("sqlite", JdbcUrls.subprotocol("jdbc:sqlite::memory:").orElseThrow());
        assertEquals("mysql", JdbcUrls.subprotocol("  JDBC:MySQL://host/db  ").orElseThrow());
        assertTrue(JdbcUrls.subprotocol("https://example.com").isEmpty());
        assertTrue(JdbcUrls.subprotocol("jdbc:").isEmpty());
        assertTrue(JdbcUrls.subprotocol(null).isEmpty());
    }

    @Test
    void resolvesTheCatalogEntryForCommonUrls() {
        assertEquals("mysql", idFor("jdbc:mysql://localhost:3306/app"));
        assertEquals("oracle", idFor("jdbc:oracle:thin:@//db.corp:1521/ORCLPDB1"));
        assertEquals("mariadb", idFor("jdbc:mariadb://localhost:3306/test"));
        assertEquals("sqlserver", idFor("jdbc:sqlserver://localhost:1433;databaseName=master"));
        assertEquals("db2", idFor("jdbc:db2://localhost:50000/sample"));
        assertEquals("h2", idFor("jdbc:h2:mem:test"));
        assertEquals("snowflake", idFor("jdbc:snowflake://acct.snowflakecomputing.com/?db=d"));
        assertEquals("redshift", idFor("jdbc:redshift://cluster:5439/dev"));
    }

    @Test
    void prefersTheCanonicalEntryWhenDriversShareAWireProtocol() {
        // CockroachDB reuses the PostgreSQL driver, so both catalog rows carry jdbc:postgresql.
        assertEquals("postgresql", idFor("jdbc:postgresql://localhost:26257/defaultdb"));
        assertEquals("postgresql", idFor("jdbc:postgres://localhost/db"));
    }

    @Test
    void clickhouseAcceptsEitherSpelling() {
        assertEquals("clickhouse", idFor("jdbc:ch://localhost:8123/default"));
        assertEquals("clickhouse", idFor("jdbc:clickhouse://localhost:8123/default"));
    }

    @Test
    void unknownOrNonJdbcUrlsResolveToNothing() {
        assertEquals(null, idFor("jdbc:informix-sqli://host/db"));
        assertEquals(null, idFor("mongodb://localhost"));
        assertEquals(null, idFor(""));
    }
}
