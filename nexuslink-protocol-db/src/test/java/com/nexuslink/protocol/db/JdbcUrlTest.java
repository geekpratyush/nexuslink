package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlTest {

    // ---- PostgreSQL: jdbc:postgresql://host:port/db?k=v&k2=v2 (default 5432) ----

    @Test
    void postgresFullWithParams() {
        JdbcUrl u = JdbcUrl.parse("jdbc:postgresql://db.example.com:5432/orders?ssl=true&applicationName=app");
        assertEquals(JdbcUrl.Vendor.POSTGRESQL, u.vendor());
        assertEquals("postgresql", u.subprotocol());
        assertEquals("db.example.com", u.host());
        assertEquals(5432, u.port());
        assertEquals("orders", u.database());
        assertEquals(Map.of("ssl", "true", "applicationName", "app"), u.params());
        assertNull(u.oracleForm());
    }

    @Test
    void postgresFillsDefaultPortWhenOmitted() {
        JdbcUrl u = JdbcUrl.parse("jdbc:postgresql://localhost/postgres");
        assertEquals(5432, u.port());
        assertEquals("localhost", u.host());
        assertEquals("postgres", u.database());
        assertTrue(u.params().isEmpty());
    }

    @Test
    void postgresMissingDatabase() {
        JdbcUrl u = JdbcUrl.parse("jdbc:postgresql://localhost:5432");
        assertEquals("localhost", u.host());
        assertEquals(5432, u.port());
        assertNull(u.database());
    }

    // ---- MySQL / MariaDB: jdbc:mysql://host:port/db?... , jdbc:mariadb://host:port/db?... (default 3306) ----

    @Test
    void mysqlFullWithParams() {
        JdbcUrl u = JdbcUrl.parse("jdbc:mysql://127.0.0.1:3307/shop?useSSL=false&serverTimezone=UTC");
        assertEquals(JdbcUrl.Vendor.MYSQL, u.vendor());
        assertEquals("127.0.0.1", u.host());
        assertEquals(3307, u.port());
        assertEquals("shop", u.database());
        assertEquals(Map.of("useSSL", "false", "serverTimezone", "UTC"), u.params());
    }

    @Test
    void mysqlDefaultPort() {
        JdbcUrl u = JdbcUrl.parse("jdbc:mysql://localhost/app");
        assertEquals(JdbcUrl.Vendor.MYSQL, u.vendor());
        assertEquals(3306, u.port());
    }

    @Test
    void mariadbDefaultPortAndVendor() {
        JdbcUrl u = JdbcUrl.parse("jdbc:mariadb://localhost/test");
        assertEquals(JdbcUrl.Vendor.MARIADB, u.vendor());
        assertEquals("mariadb", u.subprotocol());
        assertEquals(3306, u.port());
        assertEquals("test", u.database());
    }

    // ---- SQL Server: jdbc:sqlserver://host:port;databaseName=db;key=val (default 1433, ;-separated) ----

    @Test
    void sqlServerWithDatabaseNameAndProperties() {
        JdbcUrl u = JdbcUrl.parse("jdbc:sqlserver://sql.example.com:1433;databaseName=master;encrypt=false");
        assertEquals(JdbcUrl.Vendor.SQLSERVER, u.vendor());
        assertEquals("sql.example.com", u.host());
        assertEquals(1433, u.port());
        assertEquals("master", u.database());
        assertEquals(Map.of("encrypt", "false"), u.params());
    }

    @Test
    void sqlServerDefaultPortAndDatabaseNameCaseInsensitive() {
        JdbcUrl u = JdbcUrl.parse("jdbc:sqlserver://localhost;DatabaseName=sales;integratedSecurity=true");
        assertEquals(1433, u.port());
        assertEquals("sales", u.database());
        assertEquals(Map.of("integratedSecurity", "true"), u.params());
    }

    // ---- Oracle thin: modern @//host:port/service and legacy @host:port:sid (default 1521) ----

    @Test
    void oracleModernServiceForm() {
        JdbcUrl u = JdbcUrl.parse("jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1");
        assertEquals(JdbcUrl.Vendor.ORACLE, u.vendor());
        assertEquals("oracle:thin", u.subprotocol());
        assertEquals("db.example.com", u.host());
        assertEquals(1521, u.port());
        assertEquals("ORCLPDB1", u.database());
        assertEquals(JdbcUrl.OracleForm.SERVICE, u.oracleForm());
    }

    @Test
    void oracleLegacySidForm() {
        JdbcUrl u = JdbcUrl.parse("jdbc:oracle:thin:@dbhost:1521:ORCL");
        assertEquals(JdbcUrl.Vendor.ORACLE, u.vendor());
        assertEquals("dbhost", u.host());
        assertEquals(1521, u.port());
        assertEquals("ORCL", u.database());
        assertEquals(JdbcUrl.OracleForm.SID, u.oracleForm());
    }

    @Test
    void oracleModernDefaultPort() {
        JdbcUrl u = JdbcUrl.parse("jdbc:oracle:thin:@//localhost/XEPDB1");
        assertEquals(1521, u.port());
        assertEquals("XEPDB1", u.database());
        assertEquals(JdbcUrl.OracleForm.SERVICE, u.oracleForm());
    }

    // ---- SQLite: jdbc:sqlite:/path/to/file.db (file path, no host/port) ----

    @Test
    void sqliteFilePath() {
        JdbcUrl u = JdbcUrl.parse("jdbc:sqlite:/var/data/app.db");
        assertEquals(JdbcUrl.Vendor.SQLITE, u.vendor());
        assertEquals("sqlite", u.subprotocol());
        assertNull(u.host());
        assertEquals(-1, u.port());
        assertFalse(u.hasPort());
        assertEquals("/var/data/app.db", u.database());
        assertTrue(u.params().isEmpty());
    }

    @Test
    void sqliteInMemory() {
        JdbcUrl u = JdbcUrl.parse("jdbc:sqlite::memory:");
        assertEquals(JdbcUrl.Vendor.SQLITE, u.vendor());
        assertEquals(":memory:", u.database());
    }

    // ---- Round-trip: parse(u).toString() reproduces the canonical form ----

    @Test
    void roundTripCanonicalForms() {
        String[] canonical = {
                "jdbc:postgresql://db.example.com:5432/orders?ssl=true&applicationName=app",
                "jdbc:postgresql://localhost:5432/postgres",
                "jdbc:mysql://127.0.0.1:3307/shop?useSSL=false&serverTimezone=UTC",
                "jdbc:mariadb://localhost:3306/test",
                "jdbc:sqlserver://sql.example.com:1433;databaseName=master;encrypt=false",
                "jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1",
                "jdbc:oracle:thin:@dbhost:1521:ORCL",
                "jdbc:sqlite:/var/data/app.db",
        };
        for (String url : canonical) {
            assertEquals(url, JdbcUrl.parse(url).toString(), "round-trip failed for " + url);
        }
    }

    @Test
    void roundTripFillsDefaultPort() {
        // Non-canonical input (no port) normalises to the canonical form with the default port.
        assertEquals("jdbc:postgresql://localhost:5432/db",
                JdbcUrl.parse("jdbc:postgresql://localhost/db").toString());
        assertEquals("jdbc:mysql://localhost:3306/db",
                JdbcUrl.parse("jdbc:mysql://localhost/db").toString());
    }

    @Test
    void ipv6HostIsParsedAndRebuilt() {
        JdbcUrl u = JdbcUrl.parse("jdbc:postgresql://[::1]:5432/db");
        assertEquals("::1", u.host());
        assertEquals(5432, u.port());
    }

    @Test
    void paramsMapIsUnmodifiable() {
        JdbcUrl u = JdbcUrl.parse("jdbc:postgresql://localhost:5432/db?a=b");
        assertThrows(UnsupportedOperationException.class, () -> u.params().put("x", "y"));
    }

    // ---- Malformed / unrecognised input throws JdbcUrlException ----

    @Test
    void nullOrBlankThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class, () -> JdbcUrl.parse(null));
        assertThrows(JdbcUrl.JdbcUrlException.class, () -> JdbcUrl.parse("   "));
    }

    @Test
    void missingJdbcPrefixThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class,
                () -> JdbcUrl.parse("postgresql://localhost:5432/db"));
    }

    @Test
    void unknownSubprotocolThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class,
                () -> JdbcUrl.parse("jdbc:acmedb://localhost:1234/db"));
    }

    @Test
    void nonNumericPortThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class,
                () -> JdbcUrl.parse("jdbc:postgresql://localhost:notaport/db"));
    }

    @Test
    void portOutOfRangeThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class,
                () -> JdbcUrl.parse("jdbc:mysql://localhost:99999/db"));
    }

    @Test
    void oracleMissingAtSignThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class,
                () -> JdbcUrl.parse("jdbc:oracle:thin:localhost:1521:ORCL"));
    }

    @Test
    void emptyHostThrows() {
        assertThrows(JdbcUrl.JdbcUrlException.class,
                () -> JdbcUrl.parse("jdbc:postgresql:///db"));
    }
}
