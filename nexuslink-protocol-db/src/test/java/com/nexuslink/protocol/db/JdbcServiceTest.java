package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the JDBC client against an in-memory SQLite database — no external server.
 */
class JdbcServiceTest {

    @Test
    void connectsAndReportsDatabaseInfo() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            assertTrue(svc.isConnected());
            assertTrue(svc.databaseInfo().toLowerCase().contains("sqlite"));
        }
    }

    @Test
    void createsInsertsAndQueries() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);

            assertFalse(svc.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)").failed());

            QueryResult insert = svc.execute("INSERT INTO users(name) VALUES ('Alice'), ('Bob')");
            assertFalse(insert.isResultSet());
            assertEquals(2, insert.updateCount());

            QueryResult select = svc.execute("SELECT id, name FROM users ORDER BY id");
            assertTrue(select.isResultSet());
            assertEquals(java.util.List.of("id", "name"), select.columns());
            assertEquals(2, select.rowCount());
            assertEquals("Alice", select.rows().get(0).get(1));
            assertEquals("Bob", select.rows().get(1).get(1));
        }
    }

    @Test
    void rendersNullsAndReportsErrors() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE t (a TEXT, b TEXT)");
            svc.execute("INSERT INTO t(a) VALUES ('x')"); // b is null

            QueryResult ok = svc.execute("SELECT a, b FROM t");
            assertEquals("NULL", ok.rows().get(0).get(1));

            QueryResult bad = svc.execute("SELECT * FROM does_not_exist");
            assertTrue(bad.failed());
            assertNotNull(bad.errorMessage());
        }
    }

    @Test
    void bundledH2DriverWorksEndToEnd() throws Exception {
        // Proves a second bundled driver (not SQLite) resolves via DriverManager by URL.
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:h2:mem:nexustest;DB_CLOSE_DELAY=-1", "sa", "");
            assertTrue(svc.isConnected());
            assertTrue(svc.databaseInfo().toUpperCase().contains("H2"));

            svc.execute("CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))");
            svc.execute("INSERT INTO t VALUES (1, 'Eve'), (2, 'Frank')");
            QueryResult r = svc.execute("SELECT name FROM t ORDER BY id");
            assertEquals(2, r.rowCount());
            assertEquals("Eve", r.rows().get(0).get(0));
        }
    }

    @Test
    void listsAndDescribesTables() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE orders (id INTEGER, total REAL, note TEXT)");

            assertTrue(svc.listTables().stream().anyMatch(t -> t.startsWith("orders")));
            var cols = svc.describeTable("orders");
            assertEquals(3, cols.size());
            assertTrue(cols.get(0).startsWith("id"));
        }
    }
}
