package com.nexuslink.protocol.http.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A pure, in-memory model of a GraphQL schema parsed from an introspection response
 * ({@link GraphQLService#INTROSPECTION_QUERY}). It resolves the root operation types and every
 * type's fields (with each field's unwrapped type name and argument names), which is what
 * schema-aware auto-complete needs. Parsing uses Jackson (already a dependency here); the model
 * itself is dependency-free and JavaFX-free, so it is fully unit-testable from a canned JSON string.
 */
public final class GraphQLSchema {

    /** The three GraphQL root operations. */
    public enum OperationType { QUERY, MUTATION, SUBSCRIPTION }

    /** A field on a type: its name, the (unwrapped) type it returns, and its argument names. */
    public record Field(String name, String typeName, List<String> argNames) {}

    /** A schema type: its name, kind (OBJECT/SCALAR/…), and fields (empty for non-object types). */
    public record Type(String name, String kind, List<Field> fields) {}

    private final Map<OperationType, String> rootTypeNames;
    private final Map<String, Type> typesByName;

    private GraphQLSchema(Map<OperationType, String> rootTypeNames, Map<String, Type> typesByName) {
        this.rootTypeNames = rootTypeNames;
        this.typesByName = typesByName;
    }

    /**
     * Parses an introspection response. Accepts either the full {@code {"data":{"__schema":…}}} envelope
     * or a bare {@code {"__schema":…}} / {@code {"queryType":…}} object. Malformed input throws.
     */
    public static GraphQLSchema parse(String introspectionJson) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(introspectionJson == null ? "{}" : introspectionJson);
        JsonNode schema = locateSchema(root);

        Map<OperationType, String> roots = new LinkedHashMap<>();
        putRoot(roots, OperationType.QUERY, schema.path("queryType"));
        putRoot(roots, OperationType.MUTATION, schema.path("mutationType"));
        putRoot(roots, OperationType.SUBSCRIPTION, schema.path("subscriptionType"));

        Map<String, Type> types = new LinkedHashMap<>();
        for (JsonNode t : schema.path("types")) {
            String name = text(t.get("name"));
            if (name == null) continue;
            types.put(name, new Type(name, text(t.get("kind")), parseFields(t.path("fields"))));
        }
        return new GraphQLSchema(roots, types);
    }

    /** Walks down to the {@code __schema} object regardless of envelope depth. */
    private static JsonNode locateSchema(JsonNode root) {
        if (root.has("__schema")) return root.get("__schema");
        if (root.path("data").has("__schema")) return root.get("data").get("__schema");
        return root;   // already the schema object
    }

    private static void putRoot(Map<OperationType, String> roots, OperationType op, JsonNode node) {
        String name = text(node.get("name"));
        if (name != null) roots.put(op, name);
    }

    private static List<Field> parseFields(JsonNode fields) {
        List<Field> out = new ArrayList<>();
        if (!fields.isArray()) return out;
        for (JsonNode f : fields) {
            String name = text(f.get("name"));
            if (name == null) continue;
            List<String> args = new ArrayList<>();
            for (JsonNode a : f.path("args")) {
                String an = text(a.get("name"));
                if (an != null) args.add(an);
            }
            out.add(new Field(name, unwrapTypeName(f.path("type")), args));
        }
        return out;
    }

    /** Unwraps a possibly NON_NULL/LIST-wrapped type node to its underlying named type, or null. */
    private static String unwrapTypeName(JsonNode type) {
        JsonNode n = type;
        for (int guard = 0; guard < 12 && n != null && !n.isMissingNode(); guard++) {
            String name = text(n.get("name"));
            if (name != null) return name;
            n = n.get("ofType");
        }
        return null;
    }

    // ---- queries ----

    /** The name of the root type backing {@code op} (e.g. "Query"), if the schema defines it. */
    public Optional<String> rootTypeName(OperationType op) {
        return Optional.ofNullable(rootTypeNames.get(op));
    }

    /** The type with {@code name}, if present. */
    public Optional<Type> type(String name) {
        return Optional.ofNullable(typesByName.get(name));
    }

    /** All type names in schema order. */
    public List<String> typeNames() {
        return List.copyOf(typesByName.keySet());
    }

    /** The field names declared on {@code typeName} (empty when the type is unknown or has no fields). */
    public List<String> fieldNames(String typeName) {
        Type t = typesByName.get(typeName);
        if (t == null) return List.of();
        return t.fields().stream().map(Field::name).toList();
    }

    /** The field names of the root type for {@code op} (e.g. the available top-level queries). */
    public List<String> rootFields(OperationType op) {
        return rootTypeName(op).map(this::fieldNames).orElse(List.of());
    }

    /**
     * Case-insensitive prefix completion of the field names on {@code typeName}. A blank prefix returns
     * every field. Results preserve the schema's field order.
     */
    public List<String> complete(String typeName, String prefix) {
        String p = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        return fieldNames(typeName).stream()
                .filter(n -> p.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith(p))
                .toList();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
