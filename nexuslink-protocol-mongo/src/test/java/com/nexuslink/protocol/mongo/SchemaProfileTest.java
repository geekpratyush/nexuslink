package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaProfileTest {

    private static SchemaProfile.FieldProfile field(SchemaProfile profile, String path) {
        return profile.fields().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void countsHowOftenEachFieldIsPresent() {
        SchemaProfile profile = SchemaProfile.of(List.of(
                new Document("name", "ada").append("age", 36),
                new Document("name", "bob"),
                new Document("name", "cleo").append("age", 29)));
        assertEquals(3, profile.sampled());
        assertEquals(3, field(profile, "name").present());
        assertEquals(2, field(profile, "age").present());
        assertEquals(100.0, field(profile, "name").presencePercent());
        assertEquals(66.67, Math.round(field(profile, "age").presencePercent() * 100) / 100.0);
    }

    @Test
    void anOptionalFieldIsFlaggedBecauseItSilentlyMissesQueries() {
        SchemaProfile profile = SchemaProfile.of(List.of(
                new Document("a", 1), new Document("a", 1).append("b", 2)));
        assertFalse(field(profile, "a").isOptional());
        assertTrue(field(profile, "b").isOptional());
        assertEquals(List.of("b"), profile.optionalFields().stream()
                .map(SchemaProfile.FieldProfile::path).toList());
    }

    @Test
    void aFieldStoredAsTwoTypesIsFlaggedWithTheProportions() {
        SchemaProfile profile = SchemaProfile.of(List.of(
                new Document("id", 1), new Document("id", 2), new Document("id", "three"),
                new Document("id", 4)));
        SchemaProfile.FieldProfile id = field(profile, "id");
        assertTrue(id.isPolymorphic());
        assertEquals(BsonNode.BsonKind.INT32, id.dominantType());
        assertEquals("Int32 75% · String 25%", id.typeSummary());
        assertEquals(1, profile.polymorphicFields().size());
    }

    @Test
    void nullsAreCountedApartFromMissingFields() {
        SchemaProfile profile = SchemaProfile.of(Arrays.asList(
                new Document("a", null), new Document("a", 1), new Document()));
        SchemaProfile.FieldProfile a = field(profile, "a");
        assertEquals(2, a.present(), "a null value is still a present field");
        assertEquals(1, a.nulls());
        assertEquals(50.0, a.nullPercent());
        assertTrue(a.isOptional(), "the third document has no 'a' at all");
    }

    @Test
    void nestedDocumentsAreFlattenedIntoDottedPaths() {
        SchemaProfile profile = SchemaProfile.of(List.of(
                new Document("address", new Document("city", "London").append("zip", "N1"))));
        assertNotNull(field(profile, "address"));
        assertEquals(BsonNode.BsonKind.DOCUMENT, field(profile, "address").dominantType());
        assertEquals("London", "London");
        assertEquals(1, field(profile, "address.city").present());
        assertEquals(BsonNode.BsonKind.STRING, field(profile, "address.city").dominantType());
    }

    @Test
    void arraysAreReportedAsArraysNotExploded() {
        SchemaProfile profile = SchemaProfile.of(List.of(new Document("tags", List.of("a", "b"))));
        assertEquals(BsonNode.BsonKind.ARRAY, field(profile, "tags").dominantType());
        assertTrue(profile.fields().stream().noneMatch(f -> f.path().startsWith("tags.")));
    }

    @Test
    void distinctValuesAreCounted() {
        SchemaProfile profile = SchemaProfile.of(List.of(
                new Document("role", "admin"), new Document("role", "user"),
                new Document("role", "admin")));
        assertEquals(2, field(profile, "role").distinct());
    }

    @Test
    void fieldsPresentInEveryDocumentComeFirst() {
        SchemaProfile profile = SchemaProfile.of(List.of(
                new Document("rare", 1).append("always", 1),
                new Document("always", 2)));
        assertEquals("always", profile.fields().get(0).path());
    }

    @Test
    void anEmptySampleProfilesToNothing() {
        assertEquals(0, SchemaProfile.of(List.of()).sampled());
        assertTrue(SchemaProfile.of(null).fields().isEmpty());
    }

    @Test
    void aSingleTypeFieldSummarisesAsOneHundredPercent() {
        SchemaProfile profile = SchemaProfile.of(List.of(new Document("n", 1L), new Document("n", 2L)));
        assertEquals("Int64 100%", field(profile, "n").typeSummary());
        assertFalse(field(profile, "n").isPolymorphic());
    }
}
