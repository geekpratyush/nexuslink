package com.nexuslink.protocol.http.sse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * A Server-Sent Events (text/event-stream) client over the JDK HTTP client. Streams events on a
 * background daemon thread and dispatches them per the SSE wire format (data:/event:/id: fields,
 * blank line dispatches). Call {@link #disconnect()} to stop.
 */
public final class SseService {

    /** Stream callbacks (invoked off the UI thread). */
    public interface Listener {
        void onOpen(int statusCode);
        void onEvent(SseEvent event);
        void onError(Throwable error);
        void onClosed();
    }

    private volatile boolean running;
    private volatile InputStream stream;
    private Thread worker;

    public boolean isConnected() { return running; }

    public void connect(String url, Map<String, String> headers, Listener listener) {
        disconnect();
        running = true;
        worker = new Thread(() -> run(url, headers, listener), "sse-stream");
        worker.setDaemon(true);
        worker.start();
    }

    public void disconnect() {
        running = false;
        InputStream s = stream;
        if (s != null) { try { s.close(); } catch (Exception ignored) { } }
        stream = null;
    }

    private void run(String url, Map<String, String> headers, Listener listener) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15)).build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "text/event-stream").header("Cache-Control", "no-cache")
                    .header("User-Agent", "NexusLink/1.0 (https://github.com/geekpratyush/nexuslink)");
            headers.forEach((k, v) -> { if (!k.isBlank()) b.header(k, v); });

            HttpResponse<InputStream> resp = client.send(b.GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            listener.onOpen(resp.statusCode());
            stream = resp.body();

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            StringBuilder data = new StringBuilder();
            String event = null, id = null;
            while (running && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0 || event != null) {
                        String payload = data.length() > 0 ? data.substring(0, data.length() - 1) : "";
                        listener.onEvent(new SseEvent(event, id, payload));
                    }
                    data.setLength(0);
                    event = null;
                } else if (line.charAt(0) == ':') {
                    // comment / keep-alive — ignore
                } else {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1).stripLeading();
                    switch (field) {
                        case "data" -> data.append(value).append('\n');
                        case "event" -> event = value;
                        case "id" -> id = value;
                        default -> { /* retry / unknown */ }
                    }
                }
            }
        } catch (Exception e) {
            if (running) listener.onError(e);
        } finally {
            running = false;
            listener.onClosed();
        }
    }
}
