package com.nexuslink.protocol.http.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for graphql-transport-ws client framing and inbound parsing. */
class GraphQLWsProtocolTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void connectionInitFrame() throws Exception {
        assertEquals("connection_init", M.readTree(GraphQLWsProtocol.connectionInit()).get("type").asText());
    }

    @Test
    void subscribeCarriesIdQueryAndVariables() throws Exception {
        String frame = GraphQLWsProtocol.subscribe("op1", "subscription { greetings }", "{\"n\":3}");
        var node = M.readTree(frame);
        assertEquals("op1", node.get("id").asText());
        assertEquals("subscribe", node.get("type").asText());
        assertEquals("subscription { greetings }", node.get("payload").get("query").asText());
        assertEquals(3, node.get("payload").get("variables").get("n").asInt());
    }

    @Test
    void subscribeOmitsVariablesWhenBlank() throws Exception {
        var node = M.readTree(GraphQLWsProtocol.subscribe("op1", "subscription { t }", "  "));
        assertTrue(node.get("payload").get("variables") == null || node.get("payload").get("variables").isMissingNode());
    }

    @Test
    void completeFrame() throws Exception {
        var node = M.readTree(GraphQLWsProtocol.complete("op1"));
        assertEquals("op1", node.get("id").asText());
        assertEquals("complete", node.get("type").asText());
    }

    @Test
    void pongFrame() throws Exception {
        assertEquals("pong", M.readTree(GraphQLWsProtocol.pong()).get("type").asText());
    }

    @Test
    void parseNextExtractsIdTypeAndPayload() throws Exception {
        var msg = GraphQLWsProtocol.parse("{\"id\":\"op1\",\"type\":\"next\",\"payload\":{\"data\":{\"greetings\":\"Hi\"}}}");
        assertEquals(GraphQLWsProtocol.NEXT, msg.type());
        assertEquals("op1", msg.id());
        assertEquals("Hi", M.readTree(msg.payload()).get("data").get("greetings").asText());
    }

    @Test
    void parseConnectionAckHasNoId() {
        var msg = GraphQLWsProtocol.parse("{\"type\":\"connection_ack\"}");
        assertEquals(GraphQLWsProtocol.CONNECTION_ACK, msg.type());
        assertNull(msg.id());
        assertNull(msg.payload());
    }

    @Test
    void parsePingIsRecognized() {
        assertEquals(GraphQLWsProtocol.PING, GraphQLWsProtocol.parse("{\"type\":\"ping\"}").type());
    }

    @Test
    void parseMalformedIsSafe() {
        var msg = GraphQLWsProtocol.parse("not json");
        assertNull(msg.type());
    }
}
