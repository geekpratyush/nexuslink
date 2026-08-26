package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedisServerInfoTest {

    private static final String REDIS_7 = """
            # Server
            redis_version:7.2.4
            redis_mode:standalone
            os:Linux
            """;

    @Test
    void readsProductVersionAndModeFromInfo() {
        RedisServerInfo info = RedisServerInfo.parse(REDIS_7);
        assertEquals("Redis", info.product());
        assertEquals("7.2.4", info.version());
        assertEquals("standalone", info.mode());
        assertEquals(7, info.major());
        assertEquals(2, info.minor());
    }

    @Test
    void recognisesValkeyByItsOwnVersionField() {
        RedisServerInfo info = RedisServerInfo.parse("""
                # Server
                server_name:valkey
                valkey_version:8.0.1
                redis_version:7.2.4
                redis_mode:standalone
                """);
        assertEquals("Valkey", info.product());
        assertEquals("8.0.1", info.version(), "the fork's own version wins over the compatibility one");
    }

    @Test
    void recognisesDragonflyAndKeydb() {
        assertEquals("Dragonfly", RedisServerInfo.parse("dragonfly_version:1.21\nredis_version:6.2.11").product());
        assertEquals("KeyDB", RedisServerInfo.parse("server_name:keydb\nkeydb_version:6.3.4").product());
    }

    @Test
    void clusterAndSentinelModesAreRecognised() {
        assertTrue(RedisServerInfo.parse("redis_version:7.2.4\nredis_mode:cluster").isCluster());
        assertTrue(RedisServerInfo.parse("redis_version:7.2.4\nredis_mode:sentinel").isSentinel());
        assertFalse(RedisServerInfo.parse(REDIS_7).isCluster());
    }

    @Test
    void versionComparisonHandlesBothDirections() {
        RedisServerInfo info = RedisServerInfo.parse(REDIS_7);
        assertTrue(info.atLeast(6, 2));
        assertTrue(info.atLeast(7, 2));
        assertFalse(info.atLeast(7, 4));
        assertFalse(info.atLeast(8, 0));
    }

    @Test
    void anUnknownVersionNeverBlocksACommand() {
        RedisServerInfo unknown = RedisServerInfo.unknown();
        assertTrue(unknown.atLeast(7, 4), "let the server refuse rather than guessing on the client");
        assertEquals("", unknown.versionHint("FUNCTION"));
    }

    @Test
    void anOldServerGetsAnExplanatoryHintForALaterCommand() {
        RedisServerInfo old = RedisServerInfo.parse("redis_version:6.0.16\nredis_mode:standalone");
        assertTrue(old.versionHint("GETDEL").contains("6.2"), old.versionHint("GETDEL"));
        assertTrue(old.versionHint("FUNCTION").contains("7.0"));
        assertEquals("", old.versionHint("GET"), "an ancient command needs no hint");
    }

    @Test
    void aNewEnoughServerGetsNoHint() {
        assertEquals("", RedisServerInfo.parse(REDIS_7).versionHint("GETDEL"));
        assertEquals("", RedisServerInfo.parse(REDIS_7).versionHint("FUNCTION"));
        assertTrue(RedisServerInfo.parse(REDIS_7).versionHint("HEXPIRE").contains("7.4"));
    }

    @Test
    void malformedOrMissingInfoFallsBackToTheDefaults() {
        assertEquals("Redis", RedisServerInfo.parse(null).product());
        assertEquals("Redis", RedisServerInfo.parse("").product());
        assertEquals("standalone", RedisServerInfo.parse("# Server\ngibberish").mode());
        assertEquals(0, RedisServerInfo.parse("redis_version:").major());
    }

    @Test
    void aVersionWithASuffixStillParses() {
        assertEquals(7, RedisServerInfo.parse("redis_version:7.0.0-rc1").major());
    }

    @Test
    void theLabelNamesProductVersionAndMode() {
        assertEquals("Redis 7.2.4 · standalone", RedisServerInfo.parse(REDIS_7).label());
    }
}
