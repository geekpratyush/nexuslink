package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BsonNodeTest {

    @Test
    void topLevelFieldsBecomeOneNodeEach() {
        List<BsonNode> nodes = BsonNode.of(new Document("name", "ada").append("age", 36));
        assertEquals(2, nodes.size());
        assertEquals("name", nodes.get(0).name());
        assertEquals("ada", nodes.get(0).value());
        assertEquals(BsonNode.BsonKind.STRING, nodes.get(0).kind());
        assertEquals(BsonNode.BsonKind.INT32, nodes.get(1).kind());
    }

    @Test
    void numericTypesAreKeptApart() {
        Document doc = new Document("i", 1)
                .append("l", 1L)
                .append("d", 1.0)
                .append("dec", new Decimal128(new BigDecimal("1.00")));
        List<BsonNode> nodes = BsonNode.of(doc);
        assertEquals(BsonNode.BsonKind.INT32, nodes.get(0).kind());
        assertEquals(BsonNode.BsonKind.INT64, nodes.get(1).kind());
        assertEquals(BsonNode.BsonKind.DOUBLE, nodes.get(2).kind());
        assertEquals(BsonNode.BsonKind.DECIMAL128, nodes.get(3).kind());
    }

    @Test
    void anEmbeddedDocumentBecomesAnExpandableNode() {
        Document doc = new Document("address", new Document("city", "London").append("zip", "N1"));
        BsonNode address = BsonNode.of(doc).get(0);
        assertEquals(BsonNode.BsonKind.DOCUMENT, address.kind());
        assertTrue(address.hasChildren());
        assertEquals("{ 2 fields }", address.value());
        assertEquals("address.city", address.children().get(0).path());
        assertEquals("London", address.children().get(0).value());
    }

    @Test
    void anArrayIndexesItsChildrenByPosition() {
        Document doc = new Document("tags", List.of("a", "b", "c"));
        BsonNode tags = BsonNode.of(doc).get(0);
        assertEquals(BsonNode.BsonKind.ARRAY, tags.kind());
        assertEquals("[ 3 items ]", tags.value());
        assertEquals("0", tags.children().get(0).name());
        assertEquals("tags.2", tags.children().get(2).path(),
                "an array element's path is what $set needs");
    }

    @Test
    void nestingGoesAsDeepAsTheDocument() {
        Document doc = new Document("a", new Document("b", new Document("c", List.of(new Document("d", 1)))));
        BsonNode leaf = BsonNode.of(doc).get(0)
                .children().get(0)
                .children().get(0)
                .children().get(0)
                .children().get(0);
        assertEquals("a.b.c.0.d", leaf.path());
        assertEquals("1", leaf.value());
    }

    @Test
    void singularCountsReadNaturally() {
        assertEquals("{ 1 field }", BsonNode.of(new Document("x", new Document("y", 1))).get(0).value());
        assertEquals("[ 1 item ]", BsonNode.of(new Document("x", List.of(1))).get(0).value());
    }

    @Test
    void specialValuesRenderReadably() {
        Date when = Date.from(java.time.Instant.parse("2026-08-26T10:15:30Z"));
        ObjectId id = new ObjectId("64b7f2c1a2b3c4d5e6f70819");
        Document doc = new Document("when", when).append("_id", id)
                .append("nothing", null).append("blob", new org.bson.types.Binary(new byte[]{1, 2, 3}));
        List<BsonNode> nodes = BsonNode.of(doc);
        assertEquals("2026-08-26T10:15:30Z", nodes.get(0).value());
        assertEquals(BsonNode.BsonKind.DATE, nodes.get(0).kind());
        assertEquals(id.toHexString(), nodes.get(1).value());
        assertEquals(BsonNode.BsonKind.OBJECT_ID, nodes.get(1).kind());
        assertEquals("null", nodes.get(2).value());
        assertEquals(BsonNode.BsonKind.NULL, nodes.get(2).kind());
        assertEquals("Binary(3 bytes)", nodes.get(3).value());
    }

    @Test
    void anEmptyOrNullDocumentHasNoNodes() {
        assertTrue(BsonNode.of(new Document()).isEmpty());
        assertTrue(BsonNode.of(null).isEmpty());
    }

    @Test
    void flattenWalksTheWholeSubtree() {
        Document doc = new Document("a", new Document("b", 1).append("c", List.of(1, 2)));
        List<BsonNode> all = BsonNode.of(doc).get(0).flatten();
        // a, a.b, a.c, a.c.0, a.c.1
        assertEquals(5, all.size());
        assertEquals(List.of("a", "a.b", "a.c", "a.c.0", "a.c.1"),
                all.stream().map(BsonNode::path).toList());
    }

    @Test
    void nullInsideAnArrayIsStillATypedNode() {
        BsonNode array = BsonNode.of(new Document("xs", Arrays.asList("a", null))).get(0);
        assertEquals(BsonNode.BsonKind.NULL, array.children().get(1).kind());
    }

    @Test
    void containersAreFlaggedAsSuch() {
        assertTrue(BsonNode.BsonKind.DOCUMENT.isContainer());
        assertTrue(BsonNode.BsonKind.ARRAY.isContainer());
        assertFalse(BsonNode.BsonKind.STRING.isContainer());
    }
}
