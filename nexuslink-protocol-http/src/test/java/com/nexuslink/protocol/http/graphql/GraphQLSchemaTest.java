package com.nexuslink.protocol.http.graphql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphQLSchemaTest {

    // A minimal introspection response: Query root with two fields (one NON_NULL(LIST(User)) wrapped,
    // one with an arg), a Mutation root, and a User object type. No subscription.
    private static final String INTROSPECTION = """
            { "data": { "__schema": {
              "queryType": { "name": "Query" },
              "mutationType": { "name": "Mutation" },
              "subscriptionType": null,
              "types": [
                { "name": "Query", "kind": "OBJECT", "fields": [
                    { "name": "users", "args": [],
                      "type": { "kind": "NON_NULL", "name": null,
                                "ofType": { "kind": "LIST", "name": null,
                                            "ofType": { "kind": "OBJECT", "name": "User" } } } },
                    { "name": "user", "args": [ { "name": "id" } ],
                      "type": { "kind": "OBJECT", "name": "User" } }
                ] },
                { "name": "Mutation", "kind": "OBJECT", "fields": [
                    { "name": "createUser", "args": [ { "name": "input" } ],
                      "type": { "kind": "OBJECT", "name": "User" } }
                ] },
                { "name": "User", "kind": "OBJECT", "fields": [
                    { "name": "id", "args": [], "type": { "kind": "SCALAR", "name": "ID" } },
                    { "name": "name", "args": [], "type": { "kind": "SCALAR", "name": "String" } },
                    { "name": "email", "args": [], "type": { "kind": "SCALAR", "name": "String" } }
                ] }
              ]
            } } }
            """;

    @Test
    void resolvesRootOperationTypes() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        assertEquals("Query", s.rootTypeName(GraphQLSchema.OperationType.QUERY).orElseThrow());
        assertEquals("Mutation", s.rootTypeName(GraphQLSchema.OperationType.MUTATION).orElseThrow());
        assertTrue(s.rootTypeName(GraphQLSchema.OperationType.SUBSCRIPTION).isEmpty());
    }

    @Test
    void unwrapsNonNullListType() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        GraphQLSchema.Field users = s.type("Query").orElseThrow().fields().get(0);
        assertEquals("users", users.name());
        assertEquals("User", users.typeName(), "NON_NULL(LIST(User)) unwraps to User");
    }

    @Test
    void capturesFieldArguments() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        GraphQLSchema.Field user = s.type("Query").orElseThrow().fields().get(1);
        assertEquals(List.of("id"), user.argNames());
    }

    @Test
    void listsRootFields() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        assertEquals(List.of("users", "user"), s.rootFields(GraphQLSchema.OperationType.QUERY));
        assertEquals(List.of("createUser"), s.rootFields(GraphQLSchema.OperationType.MUTATION));
    }

    @Test
    void completesFieldNamesByPrefixCaseInsensitively() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        assertEquals(List.of("id"), s.complete("User", "i"));
        assertEquals(List.of("name"), s.complete("User", "NA"));
        assertEquals(List.of("id", "name", "email"), s.complete("User", ""));
        assertTrue(s.complete("User", "z").isEmpty());
    }

    @Test
    void unknownTypeGivesNoFields() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        assertTrue(s.fieldNames("Nope").isEmpty());
        assertTrue(s.type("Nope").isEmpty());
    }

    @Test
    void listsAllTypeNames() throws Exception {
        GraphQLSchema s = GraphQLSchema.parse(INTROSPECTION);
        assertEquals(List.of("Query", "Mutation", "User"), s.typeNames());
    }

    @Test
    void parsesBareSchemaEnvelope() throws Exception {
        // Without the "data" wrapper — a bare {"__schema":…}.
        String bare = "{ \"__schema\": { \"queryType\": { \"name\": \"Q\" }, \"types\": [] } }";
        GraphQLSchema s = GraphQLSchema.parse(bare);
        assertEquals("Q", s.rootTypeName(GraphQLSchema.OperationType.QUERY).orElseThrow());
        assertTrue(s.typeNames().isEmpty());
    }
}
