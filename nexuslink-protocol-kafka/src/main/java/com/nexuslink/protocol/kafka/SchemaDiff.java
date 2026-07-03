package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Compares two Avro-style record schemas, expressed as JSON, field-by-field so a UI can
 * present a side-by-side version compare. Each schema is a JSON object with a top-level
 * {@code "fields"} array whose elements carry a {@code "name"} and a {@code "type"}; the
 * type may be a plain string (e.g. {@code "string"}), a nested object (e.g. an
 * {@code array}/{@code record}, reduced to its {@code "type"}), or a JSON array — an Avro
 * union such as {@code ["null","string"]}, which is canonicalised to {@code union[null,string]}
 * preserving member order.
 *
 * <p>Fields are matched by name and classified {@link ChangeKind#ADDED ADDED} (only in the new
 * schema), {@link ChangeKind#REMOVED REMOVED} (only in the old schema) or
 * {@link ChangeKind#TYPE_CHANGED TYPE_CHANGED} (present in both but with a differing canonical
 * type). Because a union's canonical form lists its members, a field that gains or loses
 * {@code "null"} from its union simply surfaces as a {@code TYPE_CHANGED} with differing type
 * strings. Fields whose type is unchanged are counted but not listed.
 *
 * <p>The JSON is read with {@link SchemaRegistryJson}, so no external JSON library is pulled in
 * and the whole comparator is pure and offline-testable. Changes are ordered by field name for
 * stable, testable output and the returned list is immutable. Malformed JSON, a non-object
 * schema or a missing/invalid {@code "fields"} array raises {@link IllegalArgumentException}.
 */
public final class SchemaDiff {

    /** How a single field differs between the old and new schema. */
    public enum ChangeKind { ADDED, REMOVED, TYPE_CHANGED }

    /**
     * One field's difference. For {@link ChangeKind#ADDED} the {@code oldType} is {@code null};
     * for {@link ChangeKind#REMOVED} the {@code newType} is {@code null}; for
     * {@link ChangeKind#TYPE_CHANGED} both are present and differ.
     */
    public record FieldChange(String field, ChangeKind kind, String oldType, String newType) {

        public FieldChange {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(kind, "kind");
        }
    }

    private final List<FieldChange> changes;
    private final int unchanged;

    private SchemaDiff(List<FieldChange> changes, int unchanged) {
        this.changes = Collections.unmodifiableList(changes);
        this.unchanged = unchanged;
    }

    /**
     * Compares {@code oldSchema} against {@code newSchema}, both Avro record schemas as JSON,
     * and reports the field-level changes.
     *
     * @throws IllegalArgumentException if either argument is null, not valid JSON, not a JSON
     *                                  object, or lacks a well-formed {@code "fields"} array
     */
    public static SchemaDiff between(String oldSchema, String newSchema) {
        Map<String, String> oldFields = readFields(oldSchema, "old");
        Map<String, String> newFields = readFields(newSchema, "new");

        TreeSet<String> names = new TreeSet<>();
        names.addAll(oldFields.keySet());
        names.addAll(newFields.keySet());

        List<FieldChange> changes = new ArrayList<>();
        int unchanged = 0;
        for (String name : names) {
            boolean inOld = oldFields.containsKey(name);
            boolean inNew = newFields.containsKey(name);
            String oldType = oldFields.get(name);
            String newType = newFields.get(name);

            if (inNew && !inOld) {
                changes.add(new FieldChange(name, ChangeKind.ADDED, null, newType));
            } else if (inOld && !inNew) {
                changes.add(new FieldChange(name, ChangeKind.REMOVED, oldType, null));
            } else if (!Objects.equals(oldType, newType)) {
                changes.add(new FieldChange(name, ChangeKind.TYPE_CHANGED, oldType, newType));
            } else {
                unchanged++;
            }
        }
        return new SchemaDiff(changes, unchanged);
    }

    /** All field changes, ordered by field name. Immutable; empty when the schemas are identical. */
    public List<FieldChange> changes() {
        return changes;
    }

    /** The number of fields present in both schemas with an unchanged canonical type. */
    public int unchanged() {
        return unchanged;
    }

    /** The changes of the given kind, in field-name order. Immutable. */
    public List<FieldChange> changes(ChangeKind kind) {
        Objects.requireNonNull(kind, "kind");
        List<FieldChange> filtered = new ArrayList<>();
        for (FieldChange c : changes) {
            if (c.kind() == kind) filtered.add(c);
        }
        return Collections.unmodifiableList(filtered);
    }

    /** True when the schemas differ in any way (at least one change). */
    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    /**
     * True when the new schema is a backward-compatible evolution of the old one: only
     * {@link ChangeKind#ADDED} fields, with no {@link ChangeKind#REMOVED} field and no
     * {@link ChangeKind#TYPE_CHANGED} (which also covers nullability changes).
     */
    public boolean isCompatible() {
        for (FieldChange c : changes) {
            if (c.kind() != ChangeKind.ADDED) return false;
        }
        return true;
    }

    /** Parses one schema and returns an ordered field-name → canonical-type map. */
    private static Map<String, String> readFields(String schema, String which) {
        if (schema == null) throw new IllegalArgumentException("The " + which + " schema is null");

        Object root;
        try {
            root = SchemaRegistryJson.parse(schema);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The " + which + " schema is not valid JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> obj)) {
            throw new IllegalArgumentException("The " + which + " schema must be a JSON object");
        }
        Object fields = obj.get("fields");
        if (!(fields instanceof List<?> list)) {
            throw new IllegalArgumentException("The " + which + " schema is missing a \"fields\" array");
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> field)) {
                throw new IllegalArgumentException("A field in the " + which + " schema is not a JSON object");
            }
            Object name = field.get("name");
            if (!(name instanceof String fieldName)) {
                throw new IllegalArgumentException("A field in the " + which + " schema is missing a string \"name\"");
            }
            if (!field.containsKey("type")) {
                throw new IllegalArgumentException("Field \"" + fieldName + "\" in the " + which + " schema is missing a \"type\"");
            }
            result.put(fieldName, canonicalType(field.get("type")));
        }
        return result;
    }

    /**
     * Reduces an Avro type node to a canonical string. A plain string stays as-is; a union
     * (JSON array) becomes {@code union[member,member,...]} preserving order; a nested object is
     * reduced to its own {@code "type"} (recursively).
     */
    private static String canonicalType(Object type) {
        if (type instanceof String s) {
            return s;
        }
        if (type instanceof List<?> members) {
            StringBuilder b = new StringBuilder("union[");
            for (int k = 0; k < members.size(); k++) {
                if (k > 0) b.append(',');
                b.append(canonicalType(members.get(k)));
            }
            return b.append(']').toString();
        }
        if (type instanceof Map<?, ?> obj) {
            Object nested = obj.get("type");
            if (nested == null) {
                throw new IllegalArgumentException("A type object is missing its \"type\"");
            }
            return canonicalType(nested);
        }
        throw new IllegalArgumentException("Unsupported type node: " + type);
    }
}
