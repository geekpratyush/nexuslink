package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises schema/DDL export against an in-memory SQLite database. */
class SchemaExporterTest {

    @Test
    void exportsTableWithColumnsPrimaryKeyAndForeignKey() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            svc.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER, "
                    + "FOREIGN KEY(customer_id) REFERENCES customer(id))");

            String ddl = svc.exportSchema(null);   // all objects
            assertTrue(ddl.contains("CREATE TABLE \"customer\""), ddl);
            assertTrue(ddl.contains("CREATE TABLE \"orders\""), ddl);
            assertTrue(ddl.contains("\"name\""), ddl);
            assertTrue(ddl.contains("NOT NULL"), ddl);
            assertTrue(ddl.contains("PRIMARY KEY (\"id\")"), ddl);
            assertTrue(ddl.contains("FOREIGN KEY (\"customer_id\") REFERENCES \"customer\""), ddl);
        }
    }

    @Test
    void limitsExportToSelectedTables() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE a (id INTEGER PRIMARY KEY)");
            svc.execute("CREATE TABLE b (id INTEGER PRIMARY KEY)");

            String ddl = svc.exportSchema(java.util.List.of("a"));
            assertTrue(ddl.contains("CREATE TABLE \"a\""), ddl);
            assertFalse(ddl.contains("CREATE TABLE \"b\""), ddl);
        }
    }

    @Test
    void includesUniqueIndexButNotThePrimaryKeyIndex() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE person (id INTEGER PRIMARY KEY, email TEXT)");
            svc.execute("CREATE UNIQUE INDEX idx_person_email ON person(email)");

            String ddl = svc.exportSchema(java.util.List.of("person"));
            assertTrue(ddl.contains("CREATE UNIQUE INDEX \"idx_person_email\" ON \"person\" (\"email\")"), ddl);
        }
    }

    @Test
    void summarizesViews() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, n TEXT)");
            svc.execute("CREATE VIEW v_names AS SELECT n FROM t");

            String ddl = svc.exportSchema(null);
            assertTrue(ddl.contains("-- VIEW \"v_names\""), ddl);
        }
    }
}
