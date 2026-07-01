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
}
