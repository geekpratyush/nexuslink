package com.nexuslink.protocol.http.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end W3C tracing wiring for {@link RestExecutionService}: an in-process server records the
 * {@code traceparent} it receives, so we can assert the executor injects a valid header when tracing
 * is enabled and captures a matching Zipkin span.
 */
class RestExecutionTraceTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastTraceparent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/thing", exchange -> {
            lastTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private RestRequest get(String path) {
        RestRequest req = new RestRequest();
        req.setMethod("GET");
        req.setUrl(baseUrl + path);
        return req;
    }

    @Test
    void injectsTraceparentAndCapturesSpanWhenEnabled() {
        RestExecutionService exec = new RestExecutionService();
        RestRequest req = get("/api/thing");
        req.setTraceEnabled(true);

        RestResponse resp = exec.execute(req);
        assertEquals(200, resp.statusCode());

        String sent = lastTraceparent.get();
        assertNotNull(sent, "traceparent must be sent");
        TraceContext tc = TraceContext.parseTraceparent(sent);   // valid header

        assertEquals(1, exec.capturedSpans().size());
        ZipkinSpanExporter.Span span = exec.capturedSpans().get(0);
        assertEquals(tc.traceId(), span.traceId());
        assertEquals(tc.spanId(), span.id());
        assertEquals("GET /api/thing", span.name());
        assertEquals(ZipkinSpanExporter.Kind.CLIENT, span.kind());
        assertEquals("200", span.tags().get("http.status_code"));
        assertEquals("GET", span.tags().get("http.method"));
        assertTrue(span.durationMicros() >= 1);
    }

    @Test
    void sendsNoTraceparentWhenDisabled() {
        RestExecutionService exec = new RestExecutionService();
        RestResponse resp = exec.execute(get("/api/thing"));   // trace off by default
        assertEquals(200, resp.statusCode());
        assertNull(lastTraceparent.get());
        assertTrue(exec.capturedSpans().isEmpty());
    }

    @Test
    void respectsAUserSuppliedTraceparent() {
        RestExecutionService exec = new RestExecutionService();
        RestRequest req = get("/api/thing");
        req.setTraceEnabled(true);
        String mine = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        req.getHeaders().add(new RestRequest.KeyValue("traceparent", mine));

        exec.execute(req);
        assertEquals(mine, lastTraceparent.get(), "the user's own traceparent is not overridden");
        // No auto-span is captured for a user-managed traceparent.
        assertTrue(exec.capturedSpans().isEmpty());
    }

    @Test
    void clearSpansEmptiesTheBuffer() {
        RestExecutionService exec = new RestExecutionService();
        RestRequest req = get("/api/thing");
        req.setTraceEnabled(true);
        exec.execute(req);
        assertEquals(1, exec.capturedSpans().size());
        exec.clearSpans();
        assertTrue(exec.capturedSpans().isEmpty());
    }
}
