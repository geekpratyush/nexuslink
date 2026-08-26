package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static com.nexuslink.protocol.mongo.BsonNode.BsonKind.*;
import static org.junit.jupiter.api.Assertions.*;

class BsonValueParserTest {

    @Test
    void eachNumericTypeParsesToItsOwnJavaType() {
        assertEquals(Integer.valueOf(42), BsonValueParser.parse(INT32, "42"));
        assertEquals(Long.valueOf(42), BsonValueParser.parse(INT64, "42"));
        assertEquals(Double.valueOf(42), BsonValueParser.parse(DOUBLE, "42"));
        assertEquals(new Decimal128(new java.math.BigDecimal("42.50")),
                BsonValueParser.parse(DECIMAL128, "42.50"));
    }

    @Test
    void aValueTooBigForInt32SaysToChangeTheType() {
        BsonValueParser.BsonParseException e = assertThrows(BsonValueParser.BsonParseException.class,
                () -> BsonValueParser.parse(INT32, "3000000000"));
        assertTrue(e.getMessage().contains("Int64"), e.getMessage());
    }

    @Test
    void nonNumericTextIsRefusedRatherThanCoerced() {
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(INT32, "abc"));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(DOUBLE, ""));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(INT64, "1.5"));
    }

    @Test
    void booleansTakeOnlyTrueAndFalse() {
        assertEquals(Boolean.TRUE, BsonValueParser.parse(BOOLEAN, "true"));
        assertEquals(Boolean.FALSE, BsonValueParser.parse(BOOLEAN, "FALSE"));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(BOOLEAN, "1"));
    }

    @Test
    void datesAcceptIsoInstantsPlainDatesAndEpochMillis() {
        Date instant = (Date) BsonValueParser.parse(DATE, "2026-08-26T10:15:30Z");
        assertEquals("2026-08-26T10:15:30Z", instant.toInstant().toString());
        Date day = (Date) BsonValueParser.parse(DATE, "2026-08-26");
        assertEquals("2026-08-26T00:00:00Z", day.toInstant().toString());
        assertEquals(new Date(0), BsonValueParser.parse(DATE, "0"));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(DATE, "last tuesday"));
    }

    @Test
    void objectIdsMustBeValidHex() {
        assertEquals(new ObjectId("64b7f2c1a2b3c4d5e6f70819"),
                BsonValueParser.parse(OBJECT_ID, "64b7f2c1a2b3c4d5e6f70819"));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(OBJECT_ID, "nope"));
    }

    @Test
    void stringsKeepTheirWhitespaceAndNullIsNull() {
        assertEquals("  padded  ", BsonValueParser.parse(STRING, "  padded  "));
        assertNull(BsonValueParser.parse(NULL, "anything"));
    }

    @Test
    void containersCannotBeEditedAsText() {
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(DOCUMENT, "{}"));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.parse(ARRAY, "[]"));
    }

    @Test
    void theEditableTypeListCoversTheScalarsAndExcludesContainers() {
        assertTrue(BsonValueParser.editableKinds().contains(INT64));
        assertFalse(BsonValueParser.editableKinds().contains(DOCUMENT));
        assertFalse(BsonValueParser.editableKinds().contains(ARRAY));
    }

    @Test
    void theUpdateTargetsTheFieldPathNotTheWholeDocument() {
        Document update = BsonValueParser.setUpdate("address.city", "London");
        assertEquals(new Document("$set", new Document("address.city", "London")), update);
        assertEquals(new Document("$unset", new Document("tags.0", "")),
                BsonValueParser.unsetUpdate("tags.0"));
    }

    @Test
    void anEmptyPathIsRefused() {
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.setUpdate("", 1));
        assertThrows(BsonValueParser.BsonParseException.class, () -> BsonValueParser.unsetUpdate(null));
    }
}
