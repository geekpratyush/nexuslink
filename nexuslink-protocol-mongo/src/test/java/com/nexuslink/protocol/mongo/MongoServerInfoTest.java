package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MongoServerInfoTest {

    private static Map<String, Object> build(String version) {
        return Map.of("version", version, "gitVersion", "abc123");
    }

    @Test
    void readsTheVersionFromBuildInfo() {
        MongoServerInfo info = MongoServerInfo.of(build("7.0.5"), Map.of());
        assertEquals("MongoDB", info.product());
        assertEquals("7.0.5", info.version());
        assertEquals(7, info.major());
        assertEquals(0, info.minor());
    }

    @Test
    void topologyComesFromTheHelloReply() {
        assertEquals(MongoServerInfo.Topology.STANDALONE,
                MongoServerInfo.of(build("7.0.5"), Map.of("ok", 1)).topology());
        assertEquals(MongoServerInfo.Topology.REPLICA_SET,
                MongoServerInfo.of(build("7.0.5"), Map.of("setName", "rs0")).topology());
        assertEquals(MongoServerInfo.Topology.SHARDED,
                MongoServerInfo.of(build("7.0.5"), Map.of("msg", "isdbgrid")).topology());
    }

    @Test
    void theWireCompatibleImitationsAreRecognised() {
        assertEquals("Amazon DocumentDB",
                MongoServerInfo.of(Map.of("version", "5.0.0", "sysInfo", "documentdb"), Map.of()).product());
        assertEquals("Azure Cosmos DB",
                MongoServerInfo.of(Map.of("version", "4.2.0", "_t", "cosmosBuildInfo"), Map.of()).product());
        assertEquals("FerretDB",
                MongoServerInfo.of(Map.of("version", "7.0.0", "ferretdb", Map.of("version", "1.0")), Map.of()).product());
        assertTrue(MongoServerInfo.of(Map.of("version", "5.0.0", "sysInfo", "documentdb"), Map.of()).isImitation());
        assertFalse(MongoServerInfo.of(build("7.0.5"), Map.of()).isImitation());
    }

    @Test
    void collStatsIsReadThroughTheAggregationStageFrom62() {
        assertTrue(MongoServerInfo.of(build("7.0.5"), Map.of()).prefersCollStatsAggregation());
        assertTrue(MongoServerInfo.of(build("6.2.0"), Map.of()).prefersCollStatsAggregation());
        assertFalse(MongoServerInfo.of(build("6.0.12"), Map.of()).prefersCollStatsAggregation());
        assertFalse(MongoServerInfo.of(build("4.4.0"), Map.of()).prefersCollStatsAggregation());
    }

    @Test
    void transactionsNeedAReplicaSetOrShardedClusterNotJustAVersion() {
        assertFalse(MongoServerInfo.of(build("7.0.5"), Map.of()).supportsTransactions(),
                "a standalone cannot run transactions whatever its version");
        assertTrue(MongoServerInfo.of(build("7.0.5"), Map.of("setName", "rs0")).supportsTransactions());
        assertTrue(MongoServerInfo.of(build("4.2.0"), Map.of("msg", "isdbgrid")).supportsTransactions());
        assertFalse(MongoServerInfo.of(build("4.0.0"), Map.of("msg", "isdbgrid")).supportsTransactions(),
                "sharded transactions arrived in 4.2");
    }

    @Test
    void changeStreamsNeedAnOplog() {
        assertFalse(MongoServerInfo.of(build("7.0.5"), Map.of()).supportsChangeStreams());
        assertTrue(MongoServerInfo.of(build("7.0.5"), Map.of("setName", "rs0")).supportsChangeStreams());
    }

    @Test
    void userAdministrationIsUnavailableOnTheManagedImitations() {
        assertFalse(MongoServerInfo.of(Map.of("version", "5.0.0", "sysInfo", "documentdb"), Map.of())
                .supportsUserAdministration());
        assertTrue(MongoServerInfo.of(build("7.0.5"), Map.of()).supportsUserAdministration());
    }

    @Test
    void anUnknownVersionClaimsNothing() {
        MongoServerInfo unknown = MongoServerInfo.unknown();
        assertFalse(unknown.atLeast(4, 0));
        assertFalse(unknown.prefersCollStatsAggregation());
        assertEquals(0, unknown.major());
    }

    @Test
    void missingRepliesFallBackInsteadOfThrowing() {
        MongoServerInfo info = MongoServerInfo.of(null, null);
        assertEquals("MongoDB", info.product());
        assertEquals("", info.version());
        assertEquals(MongoServerInfo.Topology.STANDALONE, info.topology());
    }

    @Test
    void theLabelAndLimitationsExplainTheDeployment() {
        MongoServerInfo standalone = MongoServerInfo.of(build("7.0.5"), Map.of());
        assertEquals("MongoDB 7.0.5 · standalone", standalone.label());
        assertTrue(standalone.limitations().stream().anyMatch(l -> l.contains("transactions")));
        assertTrue(standalone.limitations().stream().anyMatch(l -> l.contains("change streams")));

        MongoServerInfo replicaSet = MongoServerInfo.of(build("7.0.5"), Map.of("setName", "rs0"));
        assertEquals("MongoDB 7.0.5 · replica set", replicaSet.label());
        assertTrue(replicaSet.limitations().isEmpty());
    }
}
