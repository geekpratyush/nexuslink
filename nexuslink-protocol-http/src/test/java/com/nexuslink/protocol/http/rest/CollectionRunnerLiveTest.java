package com.nexuslink.protocol.http.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The runner against a real HTTP server (a JDK {@link HttpServer} on a loopback port): a login
 * request whose token is extracted and used by the next request, driven by a data file.
 */
class CollectionRunnerLiveTest {

    private HttpServer server;
    private String base;
    private final ConcurrentLinkedQueue<String> seenAuth = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String user = query == null ? "?" : query.replace("user=", "");
            respond(exchange, 200, "{\"data\":{\"token\":\"tok-" + user + "\"}}");
        });
        server.createContext("/me", exchange -> {
            seenAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/fail", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private String storedLogin() {
        return "{\"method\":\"GET\",\"url\":\"" + base + "/login?user=${user}\",\"bodyType\":\"NONE\","
                + "\"assertions\":[{\"enabled\":true,\"type\":\"STATUS_EQUALS\",\"name\":\"\","
                + "\"target\":\"200\",\"max\":\"\"}],"
                + "\"extractions\":\"token = json_path: /data/token\"}";
    }

    private String storedMe() {
        return "{\"method\":\"GET\",\"url\":\"" + base + "/me\",\"bodyType\":\"NONE\","
                + "\"headers\":[{\"enabled\":true,\"key\":\"Authorization\",\"value\":\"Bearer ${token}\"}]}";
    }

    private CollectionRunner runnerFor(Map<String, String> stored) {
        RestExecutionService executor = new RestExecutionService();
        return new CollectionRunner(executor::execute,
                (id, vars) -> RestRequestJson.parse(stored.get(id), CollectionRunner.substitution(vars)),
                id -> id);
    }

    @Test
    void aTokenExtractedFromTheLoginReachesTheNextRequest() {
        Map<String, String> stored = Map.of("login", storedLogin(), "me", storedMe());
        RunReport report = runnerFor(stored).run(
                new RunPlan(List.of("login", "me"), 1, List.of(Map.of("user", "ada")), false, 0),
                Map.of(), id -> RestRequestJson.extractionsOf(stored.get(id)), null);

        assertTrue(report.isPass(), report.steps().toString());
        assertEquals(2, report.steps().size());
        assertEquals("tok-ada", report.variables().get("token"));
        assertEquals(List.of("Bearer tok-ada"), List.copyOf(seenAuth));
    }

    @Test
    void everyDataRowRunsTheRequestsOnce() {
        Map<String, String> stored = Map.of("login", storedLogin(), "me", storedMe());
        RunReport report = runnerFor(stored).run(
                new RunPlan(List.of("login", "me"), 1,
                        List.of(Map.of("user", "ada"), Map.of("user", "bob")), false, 0),
                Map.of(), id -> RestRequestJson.extractionsOf(stored.get(id)), null);

        assertEquals(4, report.steps().size(), "two rows × two requests");
        assertTrue(report.isPass());
        assertEquals(List.of("Bearer tok-ada", "Bearer tok-bob"), List.copyOf(seenAuth),
                "each iteration used its own row's token");
    }

    @Test
    void aFailingAssertionIsReportedAndCanStopTheRun() {
        String failing = "{\"method\":\"GET\",\"url\":\"" + base + "/fail\",\"bodyType\":\"NONE\","
                + "\"assertions\":[{\"enabled\":true,\"type\":\"STATUS_EQUALS\",\"name\":\"\","
                + "\"target\":\"200\",\"max\":\"\"}]}";
        Map<String, String> stored = Map.of("fail", failing, "me", storedMe());
        RunReport report = runnerFor(stored).run(
                new RunPlan(List.of("fail", "me"), 1, List.of(), true, 0),
                Map.of(), id -> List.of(), null);

        assertEquals(1, report.steps().size(), "the run stopped at the failure");
        assertFalse(report.isPass());
        assertTrue(report.stoppedEarly());
        assertEquals(500, report.steps().get(0).statusCode());
        assertTrue(seenAuth.isEmpty(), "/me was never reached");
    }

    @Test
    void aStoredRequestIsSentExactlyAsWritten() {
        RestRequest parsed = RestRequestJson.parse(storedMe(),
                CollectionRunner.substitution(Map.of("token", "xyz")));
        assertEquals(base + "/me", parsed.getUrl());
        assertEquals("Bearer xyz", parsed.getHeaders().get(0).getValue());
        RestResponse response = new RestExecutionService().execute(parsed);
        assertEquals(200, response.statusCode());
        assertEquals(List.of("Bearer xyz"), List.copyOf(seenAuth));
    }

    @Test
    void extractionRunsAgainstARealResponse() {
        RestRequest login = RestRequestJson.parse(storedLogin(),
                CollectionRunner.substitution(Map.of("user", "cleo")));
        RestResponse response = new RestExecutionService().execute(login);
        assertEquals("tok-cleo", new ResponseExtraction("token",
                ResponseExtraction.Source.JSON_PATH, "/data/token").extract(response).orElseThrow());
        assertEquals("application/json", new ResponseExtraction("ct",
                ResponseExtraction.Source.HEADER, "content-type").extract(response).orElseThrow());
    }

    @Test
    void aMultipartBodyReachesTheServerIntact() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("nexuslink-part", ".txt");
        java.nio.file.Files.writeString(file, "attached content");
        ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
        server.createContext("/upload", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bodies.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            respond(exchange, 200, "{\"ok\":true}");
        });

        RestRequest request = new RestRequest();
        request.setMethod("POST");
        request.setUrl(base + "/upload");
        request.setBodyType(RestRequest.BodyType.FORM_DATA);
        request.getFormParts().add(new RestRequest.FormPart("title", "a report"));
        request.getFormParts().add(RestRequest.FormPart.ofFile("file", file.toString()));

        RestResponse response = new RestExecutionService().execute(request);
        assertEquals(200, response.statusCode());
        String body = bodies.poll();
        String contentType = bodies.poll();
        assertNotNull(body);
        assertTrue(body.contains("name=\"title\""), body);
        assertTrue(body.contains("a report"), body);
        assertTrue(body.contains("attached content"), body);
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType);
        java.nio.file.Files.deleteIfExists(file);
    }

    @Test
    void aBinaryBodyIsSentByteForByte() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("nexuslink-bin", ".dat");
        byte[] payload = {0, 1, 2, 3, (byte) 200, (byte) 255};
        java.nio.file.Files.write(file, payload);
        ConcurrentLinkedQueue<byte[]> received = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> types = new ConcurrentLinkedQueue<>();
        server.createContext("/binary", exchange -> {
            received.add(exchange.getRequestBody().readAllBytes());
            types.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            respond(exchange, 200, "{\"ok\":true}");
        });

        RestRequest request = new RestRequest();
        request.setMethod("PUT");
        request.setUrl(base + "/binary");
        request.setBodyType(RestRequest.BodyType.BINARY);
        request.setBinaryFilePath(file.toString());
        request.setBinaryContentType("application/octet-stream");

        assertEquals(200, new RestExecutionService().execute(request).statusCode());
        assertArrayEquals(payload, received.poll(), "the bytes must arrive unchanged");
        assertEquals("application/octet-stream", types.poll());
        java.nio.file.Files.deleteIfExists(file);
    }
}
