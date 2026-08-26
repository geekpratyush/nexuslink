package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;

/**
 * Turns the text typed into the tree's Value cell back into a BSON value <em>of a chosen type</em>,
 * and builds the {@code $set} update that stores it.
 *
 * <p>This is the difference between a real Mongo tool and a JSON textbox. Saving {@code 1} without
 * saying which numeric type you meant silently rewrites an int64 field as an int32 (or a double),
 * and the next {@code $inc}, sort or range query behaves differently. Editing therefore always names
 * the target type, and this class is the only place that maps a type plus a string to a value.
 *
 * <p>Pure: no driver connection, so every conversion and every rejection is unit-testable.
 */
public final class BsonValueParser {

    private BsonValueParser() {}

    /** Thrown when the text cannot be read as the chosen type — the edit is refused, not coerced. */
    public static final class BsonParseException extends RuntimeException {
        public BsonParseException(String message) { super(message); }
    }

    /** The types a scalar field can be edited to. Containers are edited through their children. */
    public static List<BsonNode.BsonKind> editableKinds() {
        return List.of(BsonNode.BsonKind.STRING, BsonNode.BsonKind.INT32, BsonNode.BsonKind.INT64,
                BsonNode.BsonKind.DOUBLE, BsonNode.BsonKind.DECIMAL128, BsonNode.BsonKind.BOOLEAN,
                BsonNode.BsonKind.DATE, BsonNode.BsonKind.OBJECT_ID, BsonNode.BsonKind.NULL);
    }

    /**
     * Parses {@code text} as {@code kind}.
     *
     * @throws BsonParseException if the text is not a valid value of that type
     */
    public static Object parse(BsonNode.BsonKind kind, String text) {
        String t = text == null ? "" : text.trim();
        return switch (kind) {
            case NULL -> null;
            case STRING -> text == null ? "" : text;   // a string keeps its spaces
            case INT32 -> parseInt32(t);
            case INT64 -> parseInt64(t);
            case DOUBLE -> parseDouble(t);
            case DECIMAL128 -> parseDecimal(t);
            case BOOLEAN -> parseBoolean(t);
            case DATE -> parseDate(t);
            case OBJECT_ID -> parseObjectId(t);
            default -> throw new BsonParseException(kind.label() + " values cannot be edited as text");
        };
    }

    private static Integer parseInt32(String t) {
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException e) {
            throw new BsonParseException("“" + t + "” is not a 32-bit integer"
                    + (isLongButNotInt(t) ? " — it fits Int64, change the type" : ""));
        }
    }

    private static boolean isLongButNotInt(String t) {
        try {
            Long.parseLong(t);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Long parseInt64(String t) {
        try {
            return Long.valueOf(t);
        } catch (NumberFormatException e) {
            throw new BsonParseException("“" + t + "” is not a 64-bit integer");
        }
    }

    private static Double parseDouble(String t) {
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            throw new BsonParseException("“" + t + "” is not a double");
        }
    }

    private static Decimal128 parseDecimal(String t) {
        try {
            return new Decimal128(new BigDecimal(t));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BsonParseException("“" + t + "” is not a Decimal128");
        }
    }

    private static Boolean parseBoolean(String t) {
        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        throw new BsonParseException("“" + t + "” is not a boolean — use true or false");
    }

    /** ISO-8601, with or without the time part; also accepts epoch milliseconds. */
    private static Date parseDate(String t) {
        try {
            return Date.from(Instant.parse(t));
        } catch (DateTimeParseException ignored) {
            // fall through to the other accepted spellings
        }
        try {
            return Date.from(java.time.LocalDate.parse(t).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return new Date(Long.parseLong(t));
        } catch (NumberFormatException e) {
            throw new BsonParseException("“" + t + "” is not a date — use 2026-08-26T10:15:30Z, "
                    + "2026-08-26, or epoch milliseconds");
        }
    }

    private static ObjectId parseObjectId(String t) {
        if (!ObjectId.isValid(t)) {
            throw new BsonParseException("“" + t + "” is not an ObjectId (24 hex characters)");
        }
        return new ObjectId(t);
    }

    /**
     * The update document for setting one field: {@code { $set: { <path>: <value> } }}. The path is a
     * {@link BsonNode#path()}, so a nested field or an array element updates in place rather than
     * rewriting the whole document — which is what makes concurrent edits safe.
     */
    public static Document setUpdate(String path, Object value) {
        if (path == null || path.isBlank()) throw new BsonParseException("a field path is required");
        return new Document("$set", new Document(path, value));
    }

    /** The update document for removing one field: {@code { $unset: { <path>: "" } }}. */
    public static Document unsetUpdate(String path) {
        if (path == null || path.isBlank()) throw new BsonParseException("a field path is required");
        return new Document("$unset", new Document(path, ""));
    }
}
