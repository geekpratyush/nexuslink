package com.nexuslink.protocol.http.ws;

import com.nexuslink.security.tls.TlsConfig;
import com.nexuslink.security.tls.TlsContextFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * WebSocket client over the JDK's {@link java.net.http.WebSocket} (RFC 6455, zero deps).
 * Connection lifecycle and inbound frames are delivered to a {@link Listener};
 * the caller drives connect/send/close. All callbacks may arrive off the UI thread —
 * the view marshals them onto the FX thread.
 */
public final class WebSocketService {

    /** Events surfaced to the UI. */
    public interface Listener {
        void onOpen();
        void onText(String message);
        void onClosed(int code, String reason);
        void onError(Throwable error);
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile WebSocket socket;

    public boolean isConnected() {
        return socket != null && !socket.isOutputClosed();
    }

    /** Asynchronously opens a connection to {@code url}. */
    public void connect(String url, Listener listener) {
        connect(url, listener, null);
    }

    /**
     * Opens a connection with optional custom TLS material. For {@code wss://} URLs a {@code tls}
     * config with a trust store (CAs to verify the server), a client key store (mutual TLS), or
     * trust-all builds a dedicated {@code HttpClient}; otherwise the shared default client is used.
     */
    public void connect(String url, Listener listener, TlsConfig tls) {
        final HttpClient client;
        try {
            client = (tls != null && tls.isCustom())
                    ? HttpClient.newBuilder().sslContext(TlsContextFactory.create(tls)).build()
                    : http;
        } catch (Exception e) {
            listener.onError(e);
            return;
        }
        client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new Adapter(listener))
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        listener.onError(err);
                    } else {
                        this.socket = ws;
                        listener.onOpen();
                    }
                });
    }

    /** Sends a text message (no-op if not connected). */
    public void sendText(String message) {
        WebSocket ws = this.socket;
        if (ws != null) ws.sendText(message, true);
    }

    /** Initiates a normal close handshake. */
    public void close() {
        WebSocket ws = this.socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
            socket = null;
        }
    }

    /** Bridges JDK WebSocket.Listener callbacks to our simpler Listener, reassembling text frames. */
    private static final class Adapter implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder textBuffer = new StringBuilder();

        Adapter(Listener listener) { this.listener = listener; }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                listener.onText(textBuffer.toString());
                textBuffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            listener.onClosed(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            listener.onError(error);
        }
    }
}
