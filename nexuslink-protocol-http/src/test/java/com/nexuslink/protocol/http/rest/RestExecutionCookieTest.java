package com.nexuslink.protocol.http.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end cookie wiring for {@link RestExecutionService}: a tiny in-process
 * HTTP server hands out a {@code Set-Cookie} on the first call and echoes back
 * whatever {@code Cookie} header it receives, so we can assert that the session
 * jar both captures and replays cookies the way a browser would.
 */
class RestExecutionCookieTest {

    private HttpServer server;
    private String baseUrl;
    /** The Cookie header the server last received (null when none was sent). */
    private final AtomicReference<String> lastCookieHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc123; Path=/");
            byte[] body = "logged-in".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/echo", exchange -> {
            lastCookieHeader.set(exchange.getRequestHeaders().getFirst("Cookie"));
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
    void capturesAndReplaysCookieAcrossRequests() {
        RestExecutionService exec = new RestExecutionService();

        RestResponse login = exec.execute(get("/login"));
        assertEquals(200, login.statusCode());
        assertEquals(1, exec.cookieJar().all().size(), "Set-Cookie should be captured");

        RestResponse echo = exec.execute(get("/echo"));
        assertEquals(200, echo.statusCode());
        assertEquals("sid=abc123", lastCookieHeader.get(),
                "the captured cookie should be replayed on the next request");
    }

    @Test
    void disabledJarNeitherCapturesNorSends() {
        RestExecutionService exec = new RestExecutionService();
        exec.setCookieJarEnabled(false);

        exec.execute(get("/login"));
        assertTrue(exec.cookieJar().all().isEmpty(), "disabled jar must not capture");

        exec.execute(get("/echo"));
        assertNull(lastCookieHeader.get(), "disabled jar must not send a Cookie header");
    }

    @Test
    void userSuppliedCookieHeaderIsNotOverridden() {
        RestExecutionService exec = new RestExecutionService();
        exec.execute(get("/login"));   // jar now holds sid=abc123

        RestRequest req = get("/echo");
        req.getHeaders().add(new RestRequest.KeyValue("Cookie", "override=yes"));
        exec.execute(req);

        assertEquals("override=yes", lastCookieHeader.get(),
                "an explicit Cookie header should win over the jar");
    }
}
