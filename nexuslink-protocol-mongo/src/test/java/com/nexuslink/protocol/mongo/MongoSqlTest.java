package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure (no-server) tests for the SQL→Mongo translation helpers. */
class MongoSqlTest {

    @Test
    void projectionStarIsNull() {
        assertNull(MongoService.parseProjection("*"));
    }

    @Test
    void projectionListsFields() {
        Document p = MongoService.parseProjection("name, role");
        assertEquals(1, p.get("name"));
        assertEquals(1, p.get("role"));
    }

    @Test
    void whereEqualsUsesPlainValue() {
        Document f = MongoService.parseWhere("role = 'admin'");
        assertEquals("admin", f.get("role"));
    }

    @Test
    void whereComparisonAndNumberParsing() {
        Document f = MongoService.parseWhere("age >= 21 AND active = true");
        assertEquals(new Document("$gte", 21), f.get("age"));
        assertEquals(Boolean.TRUE, f.get("active"));
    }

    @Test
    void whereNotEqualAndLike() {
        Document f = MongoService.parseWhere("status != 'gone' AND name LIKE 'A%'");
        assertEquals(new Document("$ne", "gone"), f.get("status"));
        Document like = (Document) f.get("name");
        assertEquals("i", like.get("$options"));
        assertTrue(like.getString("$regex").startsWith("^"), like.getString("$regex"));
    }

    @Test
    void orderByDirection() {
        Document s = MongoService.parseOrder("name, age DESC");
        assertEquals(1, s.get("name"));
        assertEquals(-1, s.get("age"));
    }

    @Test
    void csvExportUsesUnionOfFieldsAndEscapes() {
        var docs = java.util.List.of(
                new Document("name", "Al, Jr").append("age", 30),
                new Document("name", "Bob").append("city", "NYC"));
        String csv = MongoService.toCsv(docs);
        String[] lines = csv.strip().split("\n");
        assertEquals("name,age,city", lines[0]);
        assertEquals("\"Al, Jr\",30,", lines[1]);   // comma-containing value quoted; missing city blank
        assertEquals("Bob,,NYC", lines[2]);
    }

    @Test
    void jsonArrayExportWrapsDocuments() {
        String json = MongoService.toJsonArray(java.util.List.of(new Document("a", 1), new Document("b", 2)));
        assertTrue(json.startsWith("["), json);
        assertTrue(json.contains("\"a\": 1"), json);
        assertTrue(json.trim().endsWith("]"), json);
    }
}
