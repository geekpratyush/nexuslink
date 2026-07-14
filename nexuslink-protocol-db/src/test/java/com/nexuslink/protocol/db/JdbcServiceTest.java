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
    void generatesErDiagramWithEntitiesAndRelationship() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name TEXT)");
            svc.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER, "
                    + "FOREIGN KEY(customer_id) REFERENCES customer(id))");

            String mermaid = svc.erDiagramMermaid();
            assertTrue(mermaid.startsWith("erDiagram"), mermaid);
            assertTrue(mermaid.contains("customer {"), mermaid);
            assertTrue(mermaid.contains("orders {"), mermaid);
            assertTrue(mermaid.contains("id PK"), mermaid);
            assertTrue(mermaid.contains("customer_id FK"), mermaid);
            assertTrue(mermaid.contains("customer ||--o{ orders"), mermaid);
        }
    }

    @Test
    void erDiagramLimitsToSelectedTablesAndDropsDanglingRelationships() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name TEXT)");
            svc.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER, "
                    + "FOREIGN KEY(customer_id) REFERENCES customer(id))");

            // Only 'orders' selected: its entity is drawn, but the relationship to the excluded
            // 'customer' table must NOT appear (no reference to an entity we don't draw).
            String mermaid = svc.erDiagramMermaid(java.util.List.of("orders"));
            assertTrue(mermaid.contains("orders {"), mermaid);
            assertFalse(mermaid.contains("customer {"), mermaid);
            assertFalse(mermaid.contains("customer ||--o{ orders"), mermaid);
        }
    }

    @Test
    void erDiagramWithNullSelectionIncludesEverything() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
            svc.execute("CREATE TABLE b (id INTEGER PRIMARY KEY)");
            String mermaid = svc.erDiagramMermaid(null);
            assertTrue(mermaid.contains("a {") && mermaid.contains("b {"), mermaid);
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
    void insertBuilderOutputExecutesAndOmittedPkAutoIncrements() throws Exception {
        // End-to-end proof for the "Insert row" form: the SQL SqlInsertBuilder emits is valid, the
        // omitted primary key is filled by SQLite's rowid auto-increment, and the row is queryable.
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, note TEXT)");

            String sql = new SqlInsertBuilder().table("users")
                    .value("name", "O'Brien")   // PK "id" deliberately omitted
                    .valueNull("note")
                    .build();
            QueryResult insert = svc.execute(sql);
            assertFalse(insert.failed(), sql);
            assertEquals(1, insert.updateCount());

            QueryResult select = svc.execute("SELECT id, name, note FROM users");
            assertEquals(1, select.rowCount());
            assertEquals("1", select.rows().get(0).get(0));      // auto-incremented PK
            assertEquals("O'Brien", select.rows().get(0).get(1)); // escaped quote round-tripped
            assertEquals("NULL", select.rows().get(0).get(2));    // explicit NULL
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

    @Test
    void reportsPrimaryKeyColumnsInOrder() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE t (a INTEGER, b INTEGER, note TEXT, PRIMARY KEY (a, b))");
            assertEquals(java.util.List.of("a", "b"), svc.primaryKeyColumns("t"));

            svc.execute("CREATE TABLE nopk (x INTEGER)");
            assertTrue(svc.primaryKeyColumns("nopk").isEmpty());
        }
    }

    @Test
    void rollbackDiscardsUncommittedWork() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
            assertTrue(svc.isAutoCommit());

            svc.setAutoCommit(false);
            assertFalse(svc.isAutoCommit());
            svc.execute("INSERT INTO t (id, v) VALUES (1, 'a')");
            svc.rollback();
            assertEquals(0, svc.execute("SELECT * FROM t").rowCount());

            svc.execute("INSERT INTO t (id, v) VALUES (2, 'b')");
            svc.commit();
            assertEquals(1, svc.execute("SELECT * FROM t").rowCount());
        }
    }

    @Test
    void executeAllIsAtomicAndRollsBackOnError() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");

            int n = svc.executeAll(java.util.List.of(
                    "INSERT INTO t (id, v) VALUES (1, 'a')",
                    "INSERT INTO t (id, v) VALUES (2, 'b')"));
            assertEquals(2, n);
            assertEquals(2, svc.execute("SELECT * FROM t").rowCount());
            assertTrue(svc.isAutoCommit(), "auto-commit restored after executeAll");

            // Second statement violates the PK: the whole batch must roll back, leaving 2 rows.
            assertThrows(java.sql.SQLException.class, () -> svc.executeAll(java.util.List.of(
                    "INSERT INTO t (id, v) VALUES (3, 'c')",
                    "INSERT INTO t (id, v) VALUES (1, 'dup')")));
            assertEquals(2, svc.execute("SELECT * FROM t").rowCount());
            assertTrue(svc.isAutoCommit(), "auto-commit restored after a failed executeAll");
        }
    }
}
