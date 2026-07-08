package com.nexuslink.protocol.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link JdbcConnectionPool} keying/lifecycle plus a real pooled round-trip against an
 * in-memory H2 database (offline — no external server). A named H2 {@code mem} DB with
 * {@code DB_CLOSE_DELAY=-1} survives across pooled connections, which lets us prove
 * acquire → query → release → reacquire without Docker.
 */
class JdbcConnectionPoolTest {

    private static final String H2_URL = "jdbc:h2:mem:pooltest;DB_CLOSE_DELAY=-1";

    @Test
    void keyForIsStableAndSeparatesUserAndUrl() {
        assertEquals("alice@jdbc:x", JdbcConnectionPool.keyFor("jdbc:x", "alice"));
        assertEquals("jdbc:x", JdbcConnectionPool.keyFor("jdbc:x", null));
        assertEquals("jdbc:x", JdbcConnectionPool.keyFor("jdbc:x", "   "));
        assertNotEquals(JdbcConnectionPool.keyFor("jdbc:x", "alice"),
                JdbcConnectionPool.keyFor("jdbc:x", "bob"));
    }

    @Test
    void reusesOnePoolPerKeyAndTracksLifecycle() {
        try (JdbcConnectionPool pool = new JdbcConnectionPool()) {
            String key = JdbcConnectionPool.keyFor(H2_URL, "sa");
            assertFalse(pool.hasPool(key));

            HikariDataSource a = pool.dataSourceFor(key, H2_URL, "sa", "", Map.of(), JdbcPoolConfig.defaults());
            HikariDataSource b = pool.dataSourceFor(key, H2_URL, "sa", "", Map.of(), JdbcPoolConfig.defaults());
            assertSame(a, b, "same key returns the same pool");
            assertTrue(pool.hasPool(key));
            assertEquals(1, pool.poolCount());

            assertTrue(pool.closePool(key));
            assertFalse(pool.hasPool(key));
            assertEquals(0, pool.poolCount());
            assertFalse(pool.closePool(key), "second close is a no-op");
        }
    }

    @Test
    void closeShutsDownEveryPool() {
        JdbcConnectionPool pool = new JdbcConnectionPool();
        pool.dataSourceFor(JdbcConnectionPool.keyFor(H2_URL, "sa"), H2_URL, "sa", "", Map.of(), JdbcPoolConfig.defaults());
        pool.dataSourceFor(JdbcConnectionPool.keyFor("jdbc:h2:mem:other;DB_CLOSE_DELAY=-1", "sa"),
                "jdbc:h2:mem:other;DB_CLOSE_DELAY=-1", "sa", "", Map.of(), JdbcPoolConfig.defaults());
        assertEquals(2, pool.poolCount());
        pool.close();
        assertEquals(0, pool.poolCount());
    }

    @Test
    void borrowsQueriesAndReleasesBackToPool() throws Exception {
        try (JdbcConnectionPool pool = new JdbcConnectionPool()) {
            String key = JdbcConnectionPool.keyFor(H2_URL, "sa");
            JdbcPoolConfig cfg = JdbcPoolConfig.builder().maxPoolSize(3).minIdle(1).build();

            // First borrow: create + insert.
            try (Connection c = pool.getConnection(key, H2_URL, "sa", "", Map.of(), cfg);
                 var st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, name VARCHAR(20))");
                st.execute("MERGE INTO t VALUES (1, 'alice')");
            } // released back to the pool here

            // Reacquire from the same pool and read the row back.
            try (Connection c = pool.getConnection(key, H2_URL, "sa", "", Map.of(), cfg);
                 var st = c.createStatement();
                 var rs = st.executeQuery("SELECT name FROM t WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("alice", rs.getString(1));
            }
            assertEquals(1, pool.poolCount(), "still a single shared pool after reacquire");
        }
    }

    @Test
    void jdbcServiceRunsThroughAPooledConnection() throws Exception {
        try (JdbcConnectionPool pool = new JdbcConnectionPool();
             JdbcService svc = new JdbcService()) {
            svc.connectPooled(pool, H2_URL, "sa", "", Map.of(), JdbcPoolConfig.defaults());
            assertTrue(svc.isConnected());
            assertTrue(svc.isPooled());
            assertTrue(svc.databaseInfo().toUpperCase().contains("H2"));

            svc.execute("CREATE TABLE IF NOT EXISTS pooled (id INT PRIMARY KEY, v VARCHAR(10))");
            svc.execute("MERGE INTO pooled VALUES (1, 'ok')");
            QueryResult r = svc.execute("SELECT v FROM pooled WHERE id = 1");
            assertEquals("ok", r.rows().get(0).get(0));

            // close() returns the connection to the pool; the pool stays alive for reuse.
            svc.close();
            assertFalse(svc.isPooled());
            assertEquals(1, pool.poolCount());
        }
    }
}
