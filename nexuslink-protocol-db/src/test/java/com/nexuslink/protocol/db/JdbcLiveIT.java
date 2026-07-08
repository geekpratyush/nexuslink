package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live JDBC tests against the local {@code test-env} Docker Compose stack (Postgres + MariaDB).
 * <p>
 * Bring the stack up first:
 * <pre>docker compose -f test-env/docker-compose.yml up -d postgres mariadb</pre>
 * then run with {@code -Dnexuslink.it=true}. Gated on that property so the default build stays
 * green without the stack running.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class JdbcLiveIT {

    private void roundTrip(String url, String user, String pw, String autoIncr) throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect(url, user, pw);
            assertTrue(svc.isConnected(), "connected to " + url);
            assertNotNull(svc.databaseInfo());

            svc.execute("DROP TABLE IF EXISTS nexus_it");
            QueryResult ddl = svc.execute("CREATE TABLE nexus_it (id " + autoIncr + ", name VARCHAR(64))");
            assertFalse(ddl.failed(), ddl.errorMessage());

            QueryResult ins = svc.execute("INSERT INTO nexus_it (name) VALUES ('alice')");
            assertFalse(ins.failed(), ins.errorMessage());

            QueryResult sel = svc.execute("SELECT name FROM nexus_it ORDER BY id");
            assertTrue(sel.isResultSet());
            assertEquals(1, sel.rowCount());
            assertEquals("alice", sel.rows().get(0).get(0));

            assertTrue(svc.listTables().stream().anyMatch(t -> t.toLowerCase().contains("nexus_it")));
            svc.execute("DROP TABLE nexus_it");
        }
    }

    @Test
    void postgres() throws Exception {
        roundTrip("jdbc:postgresql://localhost:5432/nexus", "nexus", "nexus", "SERIAL PRIMARY KEY");
    }

    @Test
    void mariadb() throws Exception {
        roundTrip("jdbc:mariadb://localhost:3306/nexus", "nexus", "nexus",
                "INT AUTO_INCREMENT PRIMARY KEY");
    }

    /**
     * Proves the HikariCP pool works end-to-end against a live server: acquire a pooled connection,
     * write, release it back to the pool, then reacquire from the same pool and read the row back.
     */
    private void pooledRoundTrip(String url, String user, String pw, String autoIncr) throws Exception {
        try (JdbcConnectionPool pool = new JdbcConnectionPool()) {
            JdbcPoolConfig cfg = JdbcPoolConfig.builder().maxPoolSize(4).minIdle(1).build();

            // Acquire → write → release.
            try (JdbcService svc = new JdbcService()) {
                svc.connectPooled(pool, url, user, pw, java.util.Map.of(), cfg);
                assertTrue(svc.isConnected());
                assertTrue(svc.isPooled());
                svc.execute("DROP TABLE IF EXISTS nexus_pool_it");
                QueryResult ddl = svc.execute("CREATE TABLE nexus_pool_it (id " + autoIncr + ", name VARCHAR(64))");
                assertFalse(ddl.failed(), ddl.errorMessage());
                assertFalse(svc.execute("INSERT INTO nexus_pool_it (name) VALUES ('bob')").failed());
            }

            assertEquals(1, pool.poolCount(), "pool survives the released session");

            // Reacquire from the same pool → read.
            try (JdbcService svc = new JdbcService()) {
                svc.connectPooled(pool, url, user, pw, java.util.Map.of(), cfg);
                QueryResult sel = svc.execute("SELECT name FROM nexus_pool_it ORDER BY id");
                assertTrue(sel.isResultSet());
                assertEquals("bob", sel.rows().get(0).get(0));
                svc.execute("DROP TABLE nexus_pool_it");
            }
        }
    }

    @Test
    void postgresPooled() throws Exception {
        pooledRoundTrip("jdbc:postgresql://localhost:5432/nexus", "nexus", "nexus", "SERIAL PRIMARY KEY");
    }

    @Test
    void mariadbPooled() throws Exception {
        pooledRoundTrip("jdbc:mariadb://localhost:3306/nexus", "nexus", "nexus",
                "INT AUTO_INCREMENT PRIMARY KEY");
    }
}
