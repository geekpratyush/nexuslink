package com.nexuslink.core.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuickFilterTest {

    @Test
    void aBlankQueryMatchesEverything() {
        assertTrue(QuickFilter.matches("", "Kafka"));
        assertTrue(QuickFilter.matches("   ", "Kafka"));
        assertTrue(QuickFilter.matches(null, "Kafka"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(QuickFilter.matches("KAF", "Kafka"));
        assertTrue(QuickFilter.matches("kaf", "KAFKA"));
    }

    @Test
    void aPrefixScoresAboveAWordStartWhichScoresAboveASubstring() {
        int prefix = QuickFilter.score("azure", "Azure Blob");
        int wordStart = QuickFilter.score("blob", "Azure Blob");
        int substring = QuickFilter.score("lob", "Azure Blob");
        assertTrue(prefix > wordStart, prefix + " > " + wordStart);
        assertTrue(wordStart > substring, wordStart + " > " + substring);
        assertTrue(substring > QuickFilter.NO_MATCH);
    }

    @Test
    void initialsMatchMultiWordNames() {
        assertTrue(QuickFilter.matches("asb", "Azure Service Bus"));
        assertTrue(QuickFilter.matches("gcs", "Google Cloud Storage"));
    }

    @Test
    void everyWordOfTheQueryMustMatchButOrderDoesNot() {
        assertTrue(QuickFilter.matches("prod kafka", "Kafka — prod cluster"));
        assertTrue(QuickFilter.matches("kafka prod", "Kafka — prod cluster"));
        assertFalse(QuickFilter.matches("kafka staging", "Kafka — prod cluster"));
    }

    @Test
    void nonMatchingTextScoresNoMatch() {
        assertEquals(QuickFilter.NO_MATCH, QuickFilter.score("redis", "Kafka"));
        assertFalse(QuickFilter.matches("redis", "Kafka"));
    }

    @Test
    void emptyOrNullTextNeverMatchesANonBlankQuery() {
        assertFalse(QuickFilter.matches("a", ""));
        assertFalse(QuickFilter.matches("a", null));
    }

    @Test
    void accentsAreIgnoredOnBothSides() {
        assertTrue(QuickFilter.matches("solace", "Solacé PubSub+"));
        assertTrue(QuickFilter.matches("solacé", "Solace PubSub+"));
    }

    @Test
    void aUrlIsSearchableBySubstring() {
        assertTrue(QuickFilter.matches("postgres", "jdbc:postgresql://db.internal:5432/orders"));
        assertTrue(QuickFilter.matches("5432", "jdbc:postgresql://db.internal:5432/orders"));
    }

    @Test
    void wordBoundariesIncludePunctuation() {
        assertTrue(QuickFilter.score("bus", "Azure-Service-Bus") >= 60,
                "a hyphen should start a word just like a space");
    }
}
