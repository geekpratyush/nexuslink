package com.nexuslink.protocol.db;

import com.nexuslink.plugin.ResourceNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the categorised object tree built by JdbcExplorer against in-memory SQLite. */
class JdbcExplorerTest {

    private ResourceNode child(JdbcExplorer ex, ResourceNode parent, String idPrefix) throws Exception {
        for (ResourceNode n : ex.children(parent)) if (n.id().startsWith(idPrefix)) return n;
        return null;
    }

    @Test
    void buildsCategoryFoldersAndTableChildren() throws Exception {
        try (JdbcService svc = new JdbcService()) {
            svc.connect("jdbc:sqlite::memory:", null, null);
            svc.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, email TEXT)");
            svc.execute("CREATE UNIQUE INDEX idx_customer_email ON customer(email)");
            svc.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER, "
                    + "FOREIGN KEY(customer_id) REFERENCES customer(id))");
            svc.execute("CREATE VIEW v_customer AS SELECT id FROM customer");

            JdbcExplorer ex = new JdbcExplorer(svc);
            ResourceNode db = ex.roots().get(0);

            // Database → category folders
            List<ResourceNode> folders = ex.children(db);
            ResourceNode tables = child(ex, db, "folder:tables");
            ResourceNode views = child(ex, db, "folder:views");
            assertNotNull(tables, folders.toString());
            assertNotNull(views, folders.toString());
            assertTrue(tables.label().startsWith("Tables ("), tables.label());

            // Tables folder → table nodes
            ResourceNode orders = null;
            for (ResourceNode t : ex.children(tables)) if (t.id().equals("table:orders")) orders = t;
            assertNotNull(orders);

            // orders → columns + its foreign key
            List<ResourceNode> ordersKids = ex.children(orders);
            assertTrue(ordersKids.stream().anyMatch(n -> n.kind() == ResourceNode.Kind.COLUMN));
            assertTrue(ordersKids.stream().anyMatch(n -> n.kind() == ResourceNode.Kind.FIELD
                    && n.label().contains("→ customer")), ordersKids.toString());

            // customer → its unique index shows up
            ResourceNode customer = null;
            for (ResourceNode t : ex.children(tables)) if (t.id().equals("table:customer")) customer = t;
            assertNotNull(customer);
            assertTrue(ex.children(customer).stream()
                    .anyMatch(n -> n.kind() == ResourceNode.Kind.INDEX && n.label().contains("idx_customer_email")));

            // Views folder → view node → columns only (no index/fk)
            ResourceNode view = ex.children(views).get(0);
            assertTrue(view.id().startsWith("view:"));
            assertTrue(ex.children(view).stream().allMatch(n -> n.kind() == ResourceNode.Kind.COLUMN));
        }
    }
}
