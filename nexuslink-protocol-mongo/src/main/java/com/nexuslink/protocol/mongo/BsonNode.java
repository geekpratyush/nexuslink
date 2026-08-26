package com.nexuslink.protocol.mongo;

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * One row of the document tree: a field name, its value rendered for display, the BSON type it
 * actually holds, and its children when it is a document or an array.
 *
 * <p>A Mongo document is a tree, and flattening it into table columns — the only shape the result
 * grid had — makes anything nested unreadable. This model turns a decoded {@link Document} into the
 * expandable rows Compass shows, carrying the <em>type</em> alongside the value because in Mongo the
 * difference between {@code 1} as an int32, an int64 and a double is a real difference that a plain
 * rendering hides.
 *
 * <p>{@link #path()} is the dotted path from the document root ({@code address.city},
 * {@code tags.0}), which is exactly what a {@code $set} update needs, so the same node drives
 * in-place editing.
 *
 * <p>Pure and driver-agnostic apart from the BSON value types themselves, so the whole tree shape
 * can be tested without a server.
 */
public record BsonNode(String name, String path, String value, BsonKind kind, List<BsonNode> children) {

    /** The BSON types the tree distinguishes — what Compass's type badge shows. */
    public enum BsonKind {
        DOCUMENT("Object"), ARRAY("Array"), STRING("String"), INT32("Int32"), INT64("Int64"),
        DOUBLE("Double"), DECIMAL128("Decimal128"), BOOLEAN("Boolean"), DATE("Date"),
        OBJECT_ID("ObjectId"), NULL("Null"), BINARY("Binary"), REGEX("Regex"), OTHER("Other");

        private final String label;
        BsonKind(String label) { this.label = label; }

        /** The name shown in the Type column. */
        public String label() { return label; }

        /** {@code true} for a value that holds other values. */
        public boolean isContainer() { return this == DOCUMENT || this == ARRAY; }
    }

    /** {@code true} when this node has children to expand. */
    public boolean hasChildren() { return !children.isEmpty(); }

    /** The tree for a whole document: one node per top-level field. */
    public static List<BsonNode> of(Document document) {
        if (document == null) return List.of();
        List<BsonNode> out = new ArrayList<>(document.size());
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            out.add(node(entry.getKey(), entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /** A single node (and its subtree) for {@code value} under {@code name}. */
    @SuppressWarnings("unchecked")
    public static BsonNode node(String name, String path, Object value) {
        BsonKind kind = kindOf(value);
        if (kind == BsonKind.DOCUMENT) {
            Map<String, Object> map = (Map<String, Object>) value;
            List<BsonNode> children = new ArrayList<>(map.size());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                children.add(node(e.getKey(), path + "." + e.getKey(), e.getValue()));
            }
            return new BsonNode(name, path, "{ " + map.size() + " field"
                    + (map.size() == 1 ? "" : "s") + " }", kind, children);
        }
        if (kind == BsonKind.ARRAY) {
            List<Object> list = (List<Object>) value;
            List<BsonNode> children = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                children.add(node(String.valueOf(i), path + "." + i, list.get(i)));
            }
            return new BsonNode(name, path, "[ " + list.size() + " item"
                    + (list.size() == 1 ? "" : "s") + " ]", kind, children);
        }
        return new BsonNode(name, path, render(value), kind, Collections.emptyList());
    }

    /** The BSON type of a decoded driver value. */
    public static BsonKind kindOf(Object value) {
        if (value == null) return BsonKind.NULL;
        if (value instanceof Map) return BsonKind.DOCUMENT;
        if (value instanceof List) return BsonKind.ARRAY;
        if (value instanceof String) return BsonKind.STRING;
        if (value instanceof Integer) return BsonKind.INT32;
        if (value instanceof Long) return BsonKind.INT64;
        if (value instanceof Double || value instanceof Float) return BsonKind.DOUBLE;
        if (value instanceof Decimal128) return BsonKind.DECIMAL128;
        if (value instanceof Boolean) return BsonKind.BOOLEAN;
        if (value instanceof Date) return BsonKind.DATE;
        if (value instanceof ObjectId) return BsonKind.OBJECT_ID;
        if (value instanceof Binary || value instanceof byte[]) return BsonKind.BINARY;
        if (value instanceof java.util.regex.Pattern || value instanceof org.bson.BsonRegularExpression) {
            return BsonKind.REGEX;
        }
        return BsonKind.OTHER;
    }

    /** A scalar rendered for the Value column — dates as ISO-8601, binary as its length. */
    public static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof Date date) return date.toInstant().toString();
        if (value instanceof Binary binary) return "Binary(" + binary.getData().length + " bytes)";
        if (value instanceof byte[] bytes) return "Binary(" + bytes.length + " bytes)";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    /** Every node in this subtree, depth-first — used to search and to count fields. */
    public List<BsonNode> flatten() {
        List<BsonNode> out = new ArrayList<>();
        out.add(this);
        for (BsonNode child : children) out.addAll(child.flatten());
        return out;
    }
}
