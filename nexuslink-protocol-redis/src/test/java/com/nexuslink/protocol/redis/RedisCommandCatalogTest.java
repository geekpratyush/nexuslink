package com.nexuslink.protocol.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexuslink.protocol.redis.RedisCommandCatalog.Command;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisCommandCatalogTest {

    private static final int CATALOG_SIZE = 58;

    private static List<String> names(List<Command> cmds) {
        return cmds.stream().map(Command::name).toList();
    }

    private static boolean isSorted(List<Command> cmds) {
        for (int i = 1; i < cmds.size(); i++) {
            if (cmds.get(i - 1).name().compareTo(cmds.get(i).name()) > 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    void completeIsCaseInsensitiveAndSorted() {
        List<Command> lower = RedisCommandCatalog.complete("s");
        List<Command> upper = RedisCommandCatalog.complete("S");
        assertEquals(names(lower), names(upper));
        assertTrue(isSorted(lower));
        assertFalse(lower.isEmpty());
    }

    @Test
    void completeGetIncludesGetAndGetsetButNotSet() {
        List<String> got = names(RedisCommandCatalog.complete("get"));
        assertTrue(got.contains("GET"));
        assertTrue(got.contains("GETSET"));
        assertFalse(got.contains("SET"));
    }

    @Test
    void completeMidLineUsesFirstTokenOnly() {
        List<String> got = names(RedisCommandCatalog.complete("set ke"));
        assertTrue(got.contains("SET"));
    }

    @Test
    void blankPrefixReturnsFullCatalog() {
        assertEquals(CATALOG_SIZE, RedisCommandCatalog.complete("").size());
        assertEquals(CATALOG_SIZE, RedisCommandCatalog.complete("   ").size());
    }

    @Test
    void nullPrefixReturnsFullCatalog() {
        assertEquals(CATALOG_SIZE, RedisCommandCatalog.complete(null).size());
    }

    @Test
    void noMatchPrefixReturnsEmpty() {
        assertTrue(RedisCommandCatalog.complete("zzz").isEmpty());
    }

    @Test
    void findIsCaseInsensitiveAndReportsMissing() {
        assertTrue(RedisCommandCatalog.find("get").isPresent());
        assertEquals("GET", RedisCommandCatalog.find("get").get().name());
        assertTrue(RedisCommandCatalog.find("nope").isEmpty());
        assertTrue(RedisCommandCatalog.find(null).isEmpty());
    }

    @Test
    void inGroupPubsubContainsPublishAndSubscribe() {
        List<String> pubsub = names(RedisCommandCatalog.inGroup("PubSub".toLowerCase()));
        assertTrue(pubsub.contains("PUBLISH"));
        assertTrue(pubsub.contains("SUBSCRIBE"));
        assertTrue(isSorted(RedisCommandCatalog.inGroup("pubsub")));
    }

    @Test
    void inGroupIsCaseInsensitiveAndEmptyForUnknown() {
        assertEquals(
                names(RedisCommandCatalog.inGroup("string")),
                names(RedisCommandCatalog.inGroup("STRING")));
        assertTrue(RedisCommandCatalog.inGroup("bogus").isEmpty());
        assertTrue(RedisCommandCatalog.inGroup(null).isEmpty());
    }

    @Test
    void allHasStableSizeAndIsSorted() {
        List<Command> all = RedisCommandCatalog.all();
        assertEquals(CATALOG_SIZE, all.size());
        assertTrue(isSorted(all));
    }

    @Test
    void everyCommandHasNonBlankMetadata() {
        for (Command cmd : RedisCommandCatalog.all()) {
            assertFalse(cmd.name().isBlank(), "name blank");
            assertFalse(cmd.group().isBlank(), "group blank for " + cmd.name());
            assertFalse(cmd.summary().isBlank(), "summary blank for " + cmd.name());
            assertFalse(cmd.syntax().isBlank(), "syntax blank for " + cmd.name());
            assertEquals(cmd.name(), cmd.name().toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void returnedListsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> RedisCommandCatalog.all().add(new Command("X", "string", "s", "X")));
        assertThrows(UnsupportedOperationException.class,
                () -> RedisCommandCatalog.complete("s").clear());
        assertThrows(UnsupportedOperationException.class,
                () -> RedisCommandCatalog.inGroup("string").clear());
    }
}
