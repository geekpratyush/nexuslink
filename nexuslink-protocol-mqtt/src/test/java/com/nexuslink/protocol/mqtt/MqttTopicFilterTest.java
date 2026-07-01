package com.nexuslink.protocol.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MqttTopicFilter}, covering the normative examples and rules of MQTT 3.1.1 /
 * MQTT 5.0 &sect;4.7 (Topic Names and Topic Filters).
 */
class MqttTopicFilterTest {

    private static boolean matches(String filter, String topic) {
        // Exercise both the compiled object and the one-shot static entry point.
        boolean viaObject = MqttTopicFilter.compile(filter).matches(topic);
        boolean viaStatic = MqttTopicFilter.matches(filter, topic);
        assertEquals(viaObject, viaStatic, "compiled and static match must agree");
        return viaObject;
    }

    @Nested
    class MultiLevelWildcard {

        // §4.7.1.2: "#" matches the parent level and any number of child levels.
        @Test
        void hashMatchesParentAndChildren() {
            assertTrue(matches("sport/tennis/player1/#", "sport/tennis/player1"));
            assertTrue(matches("sport/tennis/player1/#", "sport/tennis/player1/ranking"));
            assertTrue(matches("sport/tennis/player1/#", "sport/tennis/player1/score/wimbledon"));
        }

        @Test
        void hashMatchesParentOfShorterFilter() {
            // §4.7.1.2: "sport/#" also matches the singular "sport".
            assertTrue(matches("sport/#", "sport"));
            assertTrue(matches("sport/#", "sport/tennis"));
            assertTrue(matches("sport/#", "sport/tennis/player1"));
        }

        @Test
        void loneHashMatchesEverything() {
            assertTrue(matches("#", "sport"));
            assertTrue(matches("#", "sport/tennis/player1"));
            assertTrue(matches("#", "/finance"));
            assertTrue(matches("#", "a/b/c/d/e"));
        }

        @Test
        void hashDoesNotMatchDifferentParent() {
            assertFalse(matches("sport/#", "finance"));
            assertFalse(matches("sport/tennis/#", "sport/badminton"));
        }
    }

    @Nested
    class SingleLevelWildcard {

        // §4.7.1.3: "+" matches exactly one level.
        @Test
        void plusMatchesExactlyOneLevel() {
            assertTrue(matches("sport/tennis/+", "sport/tennis/player1"));
            assertTrue(matches("sport/tennis/+", "sport/tennis/player2"));
            assertFalse(matches("sport/tennis/+", "sport/tennis/player1/ranking"));
            assertFalse(matches("sport/tennis/+", "sport/tennis"));
        }

        @Test
        void plusInTheMiddle() {
            assertTrue(matches("sport/+/player1", "sport/tennis/player1"));
            assertTrue(matches("sport/+/player1", "sport/badminton/player1"));
            assertFalse(matches("sport/+/player1", "sport/tennis/player2"));
        }

        @Test
        void lonePlusMatchesOnlySingleLevelTopics() {
            assertTrue(matches("+", "sport"));
            assertFalse(matches("+", "sport/tennis"));
        }

        @Test
        void plusMatchesEmptyLevels() {
            // §4.7.1.3 example: "+/+" matches "/finance" (leading empty level + "finance").
            assertTrue(matches("+/+", "/finance"));
            // §4.7.1.3 example: "/+" matches "/finance".
            assertTrue(matches("/+", "/finance"));
            // "+" also matches an empty level, so "+/finance" matches "/finance" (empty + finance).
            assertTrue(matches("+/finance", "/finance"));
            // §4.7.1.3 example: "/finance" does NOT match "+" (single level cannot cover two levels).
            assertFalse(matches("+", "/finance"));
        }
    }

    @Nested
    class LiteralAndEmptyLevels {

        @Test
        void exactMatch() {
            assertTrue(matches("sport/tennis/player1", "sport/tennis/player1"));
            assertFalse(matches("sport/tennis/player1", "sport/tennis/player2"));
        }

        @Test
        void leadingSlashIsDistinctEmptyLevel() {
            // §4.7.1.1: a leading "/" creates a distinct (empty) first level.
            assertTrue(matches("/finance", "/finance"));
            assertFalse(matches("/finance", "finance"));
        }

        @Test
        void trailingSlashIsDistinctEmptyLevel() {
            assertTrue(matches("sport/", "sport/"));
            assertFalse(matches("sport/", "sport"));
            assertTrue(matches("sport/+", "sport/"));
        }

        @Test
        void internalEmptyLevels() {
            assertTrue(matches("a//b", "a//b"));
            assertTrue(matches("a/+/b", "a//b"));
            assertFalse(matches("a//b", "a/x/b"));
        }
    }

    @Nested
    class DollarTopicExclusion {

        // §4.7.2: a filter starting with a wildcard must not match a $-prefixed topic.
        @Test
        void hashDoesNotMatchDollarTopics() {
            assertFalse(matches("#", "$SYS"));
            assertFalse(matches("#", "$SYS/broker/clients"));
            assertFalse(matches("#", "$share/group/topic"));
        }

        @Test
        void leadingPlusDoesNotMatchDollarTopics() {
            assertFalse(matches("+/monitor/Clients", "$SYS/monitor/Clients"));
            assertFalse(matches("+", "$SYS"));
        }

        @Test
        void explicitDollarFilterMatchesDollarTopics() {
            assertTrue(matches("$SYS/#", "$SYS/broker/clients"));
            assertTrue(matches("$SYS/monitor/+", "$SYS/monitor/Clients"));
            assertTrue(matches("$SYS/#", "$SYS"));
        }

        @Test
        void wildcardAfterFirstLevelIsUnaffected() {
            // The exclusion only concerns filters that BEGIN with a wildcard.
            assertTrue(matches("$SYS/+/clients", "$SYS/broker/clients"));
        }
    }

    @Nested
    class FilterValidation {

        // §4.7.3: valid filters.
        @ParameterizedTest
        @ValueSource(strings = {
                "#", "+", "sport/tennis/#", "sport/+/player1", "+/+", "/finance",
                "$SYS/#", "sport/", "a//b", "+/tennis/#", "sport/+", "/+"
        })
        void validFilters(String filter) {
            assertTrue(MqttTopicFilter.isValidFilter(filter));
        }

        // §4.7.1.2 / §4.7.1.3: wildcards must occupy an entire level; "#" must be last.
        @ParameterizedTest
        @ValueSource(strings = {
                "", "sport/tennis#", "sport/tennis/#/ranking", "#/ranking", "sport+",
                "sp+rt", "sport/+player", "sport/#/#", "sport/te#nnis", "a/#/b", "foo+"
        })
        void invalidFilters(String filter) {
            assertFalse(MqttTopicFilter.isValidFilter(filter));
        }

        @Test
        void nullFilterIsInvalid() {
            assertFalse(MqttTopicFilter.isValidFilter(null));
        }

        @Test
        void nulCharacterIsInvalid() {
            assertFalse(MqttTopicFilter.isValidFilter("sport/\u0000/x"));
        }

        @Test
        void compileRejectsInvalidFilter() {
            assertThrows(IllegalArgumentException.class, () -> MqttTopicFilter.compile("a/#/b"));
            assertThrows(IllegalArgumentException.class, () -> MqttTopicFilter.compile(null));
        }
    }

    @Nested
    class TopicNameValidation {

        // §4.7.3: valid topic names (no wildcards).
        @ParameterizedTest
        @ValueSource(strings = {
                "sport/tennis/player1", "/finance", "sport/", "a//b", "$SYS/broker/clients",
                "single", "with space"
        })
        void validTopicNames(String name) {
            assertTrue(MqttTopicFilter.isValidTopicName(name));
        }

        // §4.7.3: wildcards are not permitted in a Topic Name.
        @ParameterizedTest
        @ValueSource(strings = {"", "sport/#", "sport/+/player1", "#", "+", "a/+", "a#"})
        void invalidTopicNames(String name) {
            assertFalse(MqttTopicFilter.isValidTopicName(name));
        }

        @Test
        void nullTopicNameIsInvalid() {
            assertFalse(MqttTopicFilter.isValidTopicName(null));
        }

        @Test
        void nulCharacterIsInvalid() {
            assertFalse(MqttTopicFilter.isValidTopicName("sport/\u0000"));
        }
    }

    @Nested
    class SpecMatchingTable {

        // Consolidated normative examples from §4.7.1, driven as a table.
        @ParameterizedTest
        @CsvSource({
                "sport/tennis/player1/#, sport/tennis/player1,          true",
                "sport/tennis/player1/#, sport/tennis/player1/ranking,  true",
                "sport/#,                sport,                         true",
                "sport/+,                sport/,                        true",
                "sport/+,                sport,                         false",
                "+/+,                    /finance,                      true",
                "/+,                     /finance,                      true",
                "+/finance,              /finance,                      true",
                "#,                      $SYS,                          false",
                "$SYS/#,                 $SYS/broker,                   true"
        })
        void normativeExamples(String filter, String topic, boolean expected) {
            assertEquals(expected, matches(filter, topic),
                    () -> filter + " vs " + topic);
        }
    }

    @Nested
    class ApiBehaviour {

        @Test
        void nullTopicNeverMatches() {
            assertFalse(MqttTopicFilter.compile("#").matches(null));
        }

        @Test
        void filterAccessorRetainsOriginal() {
            MqttTopicFilter f = MqttTopicFilter.compile("sport/+/player1");
            assertEquals("sport/+/player1", f.filter());
            assertTrue(f.toString().contains("sport/+/player1"));
        }

        @Test
        void compiledFilterIsReusable() {
            MqttTopicFilter f = MqttTopicFilter.compile("sport/+/player1");
            assertTrue(f.matches("sport/tennis/player1"));
            assertTrue(f.matches("sport/badminton/player1"));
            assertFalse(f.matches("sport/tennis/player2"));
        }
    }
}
