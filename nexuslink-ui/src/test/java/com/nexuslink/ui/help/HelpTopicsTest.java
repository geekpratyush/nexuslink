package com.nexuslink.ui.help;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the help browser against the two ways it rots: a topic registered with no file behind it
 * (the reader gets an empty page), and a file nobody registered (the reader never finds it).
 */
class HelpTopicsTest {

    private final HelpService help = HelpService.get();

    @Test
    void everyRegisteredTopicHasContentThatLoads() {
        List<String> broken = new ArrayList<>();
        for (HelpTopic topic : help.allTopics()) {
            String body = help.loadContent(topic.id());
            if (body == null || body.isBlank() || body.startsWith("# Content not found")
                    || body.length() < 200) {
                broken.add(topic.id() + " (" + (body == null ? "missing" : body.length() + " chars") + ")");
            }
        }
        assertTrue(broken.isEmpty(), "topics with missing or stub content: " + broken);
    }

    @Test
    void everyTopicHasATitleCategoryAndSearchKeywords() {
        for (HelpTopic topic : help.allTopics()) {
            assertFalse(topic.title().isBlank(), topic.id() + " has no title");
            assertFalse(topic.category().isBlank(), topic.id() + " has no category");
        }
    }

    @Test
    void topicIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (HelpTopic topic : help.allTopics()) {
            assertTrue(seen.add(topic.id()), "duplicate topic id: " + topic.id());
        }
    }

    @Test
    void theClientsThatHaveTheirOwnTabAllHaveATopic() {
        // Not every protocol needs its own page, but the big clients do — these are the ones a user
        // is most likely to press F1 inside.
        for (String id : List.of("rest-client", "databases", "mongodb", "kafka-client", "mqtt",
                "rabbitmq", "grpc", "graphql", "sftp", "ldap", "snmp", "agent", "llm-endpoints")) {
            assertTrue(help.topic(id).isPresent(), "no help topic registered for " + id);
        }
    }

    @Test
    void theCrossCuttingTopicsExist() {
        for (String id : List.of("getting-started", "menu-reference", "keyboard-shortcuts",
                "environment-vars", "security", "tls-mtls", "certificate-manager",
                "troubleshooting", "distribution", "code-generation", "metrics", "plugins")) {
            assertTrue(help.topic(id).isPresent(), "no help topic registered for " + id);
        }
    }

    @Test
    void searchFindsAMenuByName() {
        // The menu reference is only useful if searching for a menu name reaches it.
        for (String query : List.of("menu", "toolbar", "connection menu")) {
            assertTrue(help.search(query).stream().anyMatch(r -> r.topic().id().equals("menu-reference")),
                    "searching for \"" + query + "\" did not find the menu reference");
        }
    }

    @Test
    void searchFindsAClientByItsEverydayName() {
        assertTrue(help.search("gridfs").stream().anyMatch(r -> r.topic().id().equals("mongodb")));
        assertTrue(help.search("tombstone").stream().anyMatch(r -> r.topic().id().equals("kafka-client")));
        assertTrue(help.search("artifactory").stream().anyMatch(r -> r.topic().id().equals("distribution")));
    }
}
