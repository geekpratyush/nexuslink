package com.nexuslink.protocol.kafka;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link SchemaRegistryClient} against a loopback {@link HttpServer} serving canned
 * Schema-Registry responses — no live registry needed.
 */
class SchemaRegistryClientTest {

    private HttpServer server;
    private SchemaRegistryClient client;
    private volatile String lastPostBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/subjects", "[\"users-value\",\"orders-value\"]");
        respond("/subjects/users-value/versions", "[1,2]");
        respond("/subjects/users-value/versions/2",
                "{\"subject\":\"users-value\",\"version\":2,\"id\":7,\"schema\":\"{\\\"type\\\":\\\"record\\\"}\"}");
        respond("/schemas/ids/7", "{\"schema\":\"{\\\"type\\\":\\\"record\\\"}\"}");
        server.createContext("/subjects/events-value/versions", exchange -> {
            lastPostBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            write(exchange, "{\"id\":42}");
        });
        server.start();
        client = new SchemaRegistryClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, String body) {
        server.createContext(path, exchange -> write(exchange, body));
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void listsSubjects() throws IOException {
        assertEquals(java.util.List.of("users-value", "orders-value"), client.listSubjects());
    }

    @Test
    void listsVersions() throws IOException {
        assertEquals(java.util.List.of(1, 2), client.listVersions("users-value"));
    }

    @Test
    void fetchesSchema() throws IOException {
        SchemaRegistryClient.Schema s = client.getSchema("users-value", 2);
        assertEquals("users-value", s.subject());
        assertEquals(2, s.version());
        assertEquals(7, s.id());
        assertEquals("{\"type\":\"record\"}", s.schema());
    }

    @Test
    void fetchesSchemaById() throws IOException {
        assertEquals("{\"type\":\"record\"}", client.getSchemaById(7));
    }

    @Test
    void registersSchemaAndSendsEscapedBody() throws IOException {
        int id = client.register("events-value", "{\"type\":\"string\"}");
        assertEquals(42, id);
        assertEquals("{\"schema\":\"{\\\"type\\\":\\\"string\\\"}\"}", lastPostBody);
    }
}
