package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisTopologyTest {

    @Test
    void plainUriIsStandalone() {
        assertEquals(RedisTopology.STANDALONE, RedisTopology.of("redis://localhost:6379"));
        assertEquals(RedisTopology.STANDALONE, RedisTopology.of("rediss://localhost:6379/2"));
    }

    @Test
    void sentinelSchemeIsRecognised() {
        assertEquals(RedisTopology.SENTINEL,
                RedisTopology.of("redis-sentinel://host1:26379,host2:26379/0#mymaster"));
    }

    @Test
    void clusterSchemeIsRecognised() {
        assertEquals(RedisTopology.CLUSTER, RedisTopology.of("redis-cluster://h1:6379,h2:6379"));
        assertEquals(RedisTopology.CLUSTER, RedisTopology.of("rediss-cluster://h1:6379"));
    }

    @Test
    void schemeMatchIsCaseInsensitiveAndTrimmed() {
        assertEquals(RedisTopology.CLUSTER, RedisTopology.of("  REDIS-CLUSTER://h1:6379  "));
    }

    @Test
    void nullOrBlankIsStandalone() {
        assertEquals(RedisTopology.STANDALONE, RedisTopology.of(null));
        assertEquals(RedisTopology.STANDALONE, RedisTopology.of("   "));
    }

    @Test
    void nonClusterUriPassesThroughUntouched() {
        assertEquals(List.of("redis://localhost:6379"), RedisTopology.seedUris("redis://localhost:6379"));
        String sentinel = "redis-sentinel://host1:26379,host2:26379/0#mymaster";
        assertEquals(List.of(sentinel), RedisTopology.seedUris(sentinel));
    }

    @Test
    void clusterUriExpandsToOneUriPerSeed() {
        assertEquals(List.of("redis://h1:6379", "redis://h2:6380", "redis://h3:6381"),
                RedisTopology.seedUris("redis-cluster://h1:6379,h2:6380,h3:6381"));
    }

    @Test
    void userinfoAndDatabaseRideOntoEverySeed() {
        assertEquals(List.of("redis://:secret@h1:6379/3", "redis://:secret@h2:6379/3"),
                RedisTopology.seedUris("redis-cluster://:secret@h1:6379,h2:6379/3"));
    }

    @Test
    void tlsClusterSchemeYieldsRedissSeeds() {
        assertEquals(List.of("rediss://h1:6379", "rediss://h2:6379"),
                RedisTopology.seedUris("rediss-cluster://h1:6379,h2:6379"));
    }

    /** A password may legitimately contain '@', so the split must be on the LAST one. */
    @Test
    void passwordContainingAtSignSurvives() {
        assertEquals(List.of("redis://user:p@ss@h1:6379"),
                RedisTopology.seedUris("redis-cluster://user:p@ss@h1:6379"));
    }

    @Test
    void blankSeedsAndStrayWhitespaceAreIgnored() {
        assertEquals(List.of("redis://h1:6379", "redis://h2:6379"),
                RedisTopology.seedUris("redis-cluster://h1:6379, ,  h2:6379 "));
    }

    @Test
    void singleSeedClusterIsLegal() {
        assertEquals(List.of("redis://h1:6379"), RedisTopology.seedUris("redis-cluster://h1:6379"));
    }

    // ---- sentinel ----

    @Test
    void masterNameComesFromTheFragment() {
        assertEquals("cache", RedisTopology.masterName("redis-sentinel://h1:26379#cache"));
    }

    @Test
    void masterNameDefaultsToMymaster() {
        assertEquals("mymaster", RedisTopology.masterName("redis-sentinel://h1:26379"));
        assertEquals("mymaster", RedisTopology.masterName("redis-sentinel://h1:26379#"));
        assertEquals("mymaster", RedisTopology.masterName(null));
    }

    @Test
    void sentinelSeedsSplitOnCommaAndDropTheFragment() {
        assertEquals(List.of("redis://h1:26379", "redis://h2:26380"),
                RedisTopology.sentinelSeedUris("redis-sentinel://h1:26379,h2:26380#cache"));
    }

    @Test
    void sentinelPortDefaultsTo26379() {
        assertEquals(List.of("redis://h1:26379", "redis://h2:26379"),
                RedisTopology.sentinelSeedUris("redis-sentinel://h1,h2:26379"));
    }

    @Test
    void sentinelSeedsCarryUserinfoAndDropTheDatabase() {
        assertEquals(List.of("redis://:pw@h1:26379"),
                RedisTopology.sentinelSeedUris("redis-sentinel://:pw@h1:26379/2#cache"));
    }

    @Test
    void sentinelSeedsOfANonSentinelUriAreEmpty() {
        assertEquals(List.of(), RedisTopology.sentinelSeedUris("redis://localhost:6379"));
        assertEquals(List.of(), RedisTopology.sentinelSeedUris(null));
    }

    /** An IPv6 literal's internal colons must not be mistaken for a port. */
    @Test
    void ipv6SentinelHostWithoutPortStillGetsTheDefault() {
        assertEquals(List.of("redis://[::1]:26379"),
                RedisTopology.sentinelSeedUris("redis-sentinel://[::1]"));
    }

    // ---- subscriber resolution ----

    @Test
    void subscriberResolvesAStandaloneUriToItself() {
        assertEquals("redis://localhost:6379", RedisSubscriber.resolve("redis://localhost:6379"));
    }

    @Test
    void subscriberResolvesAClusterUriToItsFirstSeed() {
        assertEquals("redis://h1:6379", RedisSubscriber.resolve("redis-cluster://h1:6379,h2:6379"));
    }
}
