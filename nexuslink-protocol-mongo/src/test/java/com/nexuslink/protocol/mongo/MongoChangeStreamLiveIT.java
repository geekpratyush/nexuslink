package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Change streams against the single-node replica set in {@code test-env} (port 27018) — a standalone
 * has no oplog and cannot exercise this at all, which is itself asserted here.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class MongoChangeStreamLiveIT {

    private MongoService replicaSet;
    private MongoService standalone;

    @BeforeEach
    void setUp() {
        replicaSet = new MongoService();
        replicaSet.connect("mongodb://localhost:27018/?replicaSet=nexusrs&directConnection=true");
        replicaSet.useDatabase("nexuslink_watch_it");
        replicaSet.runShell("db.events.drop()");

        standalone = new MongoService();
        standalone.connect("mongodb://localhost:27017");
        standalone.useDatabase("nexuslink_watch_it");
    }

    @AfterEach
    void tearDown() {
        replicaSet.runShell("db.events.drop()");
        replicaSet.close();
        standalone.close();
    }

    @Test
    void theReplicaSetIsRecognisedAsSupportingChangeStreams() {
        MongoServerInfo info = replicaSet.serverInfo();
        assertEquals(MongoServerInfo.Topology.REPLICA_SET, info.topology());
        assertTrue(info.supportsChangeStreams());
        assertTrue(info.supportsTransactions());
    }

    @Test
    void insertsUpdatesAndDeletesAllArriveOnTheStream() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        try (var watch = replicaSet.watchChanges("events", "", e -> { seen.add(e); three.countDown(); },
                error -> fail("stream failed: " + error))) {
            Thread.sleep(500);   // let the cursor open before writing
            replicaSet.runShell("db.events.insertOne({\"_id\":1,\"n\":1})");
            replicaSet.runShell("db.events.updateOne({\"_id\":1}, {\"$set\":{\"n\":2}})");
            replicaSet.runShell("db.events.deleteOne({\"_id\":1})");
            assertTrue(three.await(15, TimeUnit.SECONDS), "expected 3 changes, saw " + seen);
        }
        assertEquals(List.of("insert", "update", "delete"),
                seen.stream().map(ChangeEvent::operation).toList());
        assertTrue(seen.get(1).detail().contains("\"n\": 2"), seen.get(1).detail());
        assertTrue(seen.get(0).isInsertOrDelete());
        assertFalse(seen.get(1).isInsertOrDelete());
        assertTrue(seen.get(0).line().contains("nexuslink_watch_it.events"), seen.get(0).line());
    }

    @Test
    void stoppingTheWatchEndsIt() throws Exception {
        List<ChangeEvent> seen = new CopyOnWriteArrayList<>();
        var watch = replicaSet.watchChanges("events", "", seen::add, error -> { });
        Thread.sleep(500);
        watch.close();
        Thread.sleep(200);
        replicaSet.runShell("db.events.insertOne({\"after\":\"stop\"})");
        Thread.sleep(1000);
        assertTrue(seen.isEmpty(), "nothing should arrive after the watch is closed: " + seen);
    }

    @Test
    void aStandaloneIsRefusedWithAnExplanationRatherThanADriverError() {
        assertFalse(standalone.serverInfo().supportsChangeStreams());
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> standalone.watchChanges("events", "", event -> { }, error -> { }));
        assertTrue(e.getMessage().contains("replica set"), e.getMessage());
    }

    @Test
    void theTopologyPanelListsTheReplicaSetMembers() {
        List<String> status = replicaSet.topologyStatus();
        assertTrue(status.get(0).contains("nexusrs"), status.toString());
        assertTrue(status.stream().anyMatch(l -> l.contains("PRIMARY")), status.toString());
    }
}
