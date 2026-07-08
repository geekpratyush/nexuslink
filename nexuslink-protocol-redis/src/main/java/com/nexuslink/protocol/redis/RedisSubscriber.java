package com.nexuslink.protocol.redis;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A Redis Pub/Sub subscriber over a dedicated raw-socket connection, built purely on the hand-rolled
 * {@link RespCodec} (no external Redis client library).
 *
 * <p>Open one with {@link #connect(String, Consumer)} (a {@code redis://} / {@code rediss://} URI),
 * then {@link #subscribe(String...)} to channels and/or {@link #psubscribe(String...)} to patterns.
 * A daemon reader thread consumes push frames continuously: delivered {@link RedisMessage}s go to the
 * {@code onMessage} callback, and — if supplied — {@link RedisSubscriptionEvent} confirmations go to
 * the {@code onEvent} callback. {@link #unsubscribe(String...)} / {@link #punsubscribe(String...)}
 * (with no arguments, all) stop individual subscriptions; {@link #close()} stops the reader thread
 * and closes the socket.
 *
 * <p>The instance is thread-safe: command writes are serialised on an internal lock, so any thread
 * may call the subscribe/unsubscribe methods while the reader thread runs. Callbacks are invoked on
 * the reader thread and should not block; a UI layer should marshal them onto its own thread.
 */
public final class RedisSubscriber implements AutoCloseable {

    private final RespCodec codec = new RespCodec();
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();

    private final Consumer<RedisMessage> onMessage;
    private final Consumer<RedisSubscriptionEvent> onEvent;
    private final Consumer<Throwable> onError;

    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final Set<String> patterns = ConcurrentHashMap.newKeySet();

    private final Thread reader;
    private volatile boolean running = true;

    // ------------------------------------------------------------------ factories

    /** Connects and delivers messages to {@code onMessage}; subscription confirmations are ignored. */
    public static RedisSubscriber connect(String uri, Consumer<RedisMessage> onMessage) {
        return connect(uri, onMessage, null, null);
    }

    /**
     * Connects to {@code uri} (a {@code redis://[:password@]host:port} or {@code rediss://…} URI),
     * authenticating if the URI carries a password, and starts reading push frames.
     *
     * @param onMessage receives every delivered {@link RedisMessage} (required)
     * @param onEvent   receives subscription-state confirmations, or {@code null} to ignore them
     * @param onError   receives an unexpected reader-thread error, or {@code null} to ignore it
     */
    public static RedisSubscriber connect(String uri,
                                          Consumer<RedisMessage> onMessage,
                                          Consumer<RedisSubscriptionEvent> onEvent,
                                          Consumer<Throwable> onError) {
        Endpoint ep = Endpoint.parse(uri);
        Socket socket = null;
        try {
            socket = ep.tls()
                    ? SSLSocketFactory.getDefault().createSocket(ep.host(), ep.port())
                    : new Socket(ep.host(), ep.port());
            InputStream rawIn = new BufferedInputStream(socket.getInputStream());
            OutputStream rawOut = socket.getOutputStream();
            RespCodec handshake = new RespCodec();
            if (ep.password() != null) {
                byte[] auth = ep.user() != null
                        ? handshake.encodeCommand("AUTH", ep.user(), ep.password())
                        : handshake.encodeCommand("AUTH", ep.password());
                rawOut.write(auth);
                rawOut.flush();
                RespValue reply = handshake.decode(rawIn);
                if (reply instanceof RespValue.RespError e) {
                    throw new RespException("AUTH failed: " + e.message());
                }
            }
            return new RedisSubscriber(socket, rawIn, rawOut, onMessage, onEvent, onError);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new UncheckedIOException("could not connect to Redis at " + uri, e);
        } catch (RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private RedisSubscriber(Socket socket, InputStream in, OutputStream out,
                            Consumer<RedisMessage> onMessage,
                            Consumer<RedisSubscriptionEvent> onEvent,
                            Consumer<Throwable> onError) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.onMessage = Objects.requireNonNull(onMessage, "onMessage");
        this.onEvent = onEvent;
        this.onError = onError;
        this.reader = new Thread(this::readLoop, "redis-subscriber-" + socket.getRemoteSocketAddress());
        this.reader.setDaemon(true);
        this.reader.start();
    }

    // ------------------------------------------------------------------ subscription commands

    /** Subscribes to one or more exact channels. */
    public void subscribe(String... channels) {
        if (channels.length == 0) {
            throw new IllegalArgumentException("subscribe needs at least one channel");
        }
        send("SUBSCRIBE", channels);
        Collections.addAll(this.channels, channels);
    }

    /** Subscribes to one or more glob-style channel patterns (e.g. {@code news.*}). */
    public void psubscribe(String... patterns) {
        if (patterns.length == 0) {
            throw new IllegalArgumentException("psubscribe needs at least one pattern");
        }
        send("PSUBSCRIBE", patterns);
        Collections.addAll(this.patterns, patterns);
    }

    /** Unsubscribes from the given channels, or — with no arguments — from all channels. */
    public void unsubscribe(String... channels) {
        send("UNSUBSCRIBE", channels);
        if (channels.length == 0) {
            this.channels.clear();
        } else {
            for (String c : channels) {
                this.channels.remove(c);
            }
        }
    }

    /** Unsubscribes from the given patterns, or — with no arguments — from all patterns. */
    public void punsubscribe(String... patterns) {
        send("PUNSUBSCRIBE", patterns);
        if (patterns.length == 0) {
            this.patterns.clear();
        } else {
            for (String p : patterns) {
                this.patterns.remove(p);
            }
        }
    }

    /** The exact channels this subscriber has requested (a snapshot copy). */
    public Set<String> channels() {
        return Set.copyOf(channels);
    }

    /** The patterns this subscriber has requested (a snapshot copy). */
    public Set<String> patterns() {
        return Set.copyOf(patterns);
    }

    /** {@code true} until {@link #close()} or the connection is lost. */
    public boolean isRunning() {
        return running;
    }

    private void send(String command, String... args) {
        String[] full = new String[args.length + 1];
        full[0] = command;
        System.arraycopy(args, 0, full, 1, args.length);
        byte[] bytes = codec.encodeCommand(full);
        synchronized (writeLock) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to send " + command + " to Redis", e);
            }
        }
    }

    // ------------------------------------------------------------------ reader thread

    private void readLoop() {
        while (running) {
            RespValue frame;
            try {
                frame = codec.decode(in);
            } catch (RuntimeException e) {
                // Socket closed by close(), stream ended, or a framing desync: stop reading.
                if (running) {
                    reportError(e);
                }
                break;
            }
            try {
                dispatch(frame);
            } catch (RuntimeException e) {
                if (running) {
                    reportError(e);
                }
            }
        }
    }

    private void dispatch(RespValue frame) {
        RedisPubSubEvent event = RedisPubSubEvent.parse(frame);
        if (event instanceof RedisMessage m) {
            onMessage.accept(m);
        } else if (event instanceof RedisSubscriptionEvent se && onEvent != null) {
            onEvent.accept(se);
        }
    }

    private void reportError(Throwable t) {
        if (onError != null) {
            try {
                onError.accept(t);
            } catch (RuntimeException ignored) {
                // never let an error callback derail the reader thread
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void close() {
        running = false;
        closeQuietly(socket);
        reader.interrupt();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** Parsed connection endpoint from a {@code redis://} / {@code rediss://} URI. */
    private record Endpoint(String host, int port, String user, String password, boolean tls) {

        static Endpoint parse(String uri) {
            if (uri == null || uri.isBlank()) {
                throw new RespException("a Redis URI is required");
            }
            try {
                URI u = URI.create(uri.trim());
                String scheme = u.getScheme() == null ? "redis" : u.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("redis") && !scheme.equals("rediss")) {
                    throw new RespException("unsupported Redis URI scheme: " + scheme);
                }
                boolean tls = scheme.equals("rediss");
                String host = (u.getHost() == null || u.getHost().isBlank()) ? "localhost" : u.getHost();
                int port = u.getPort() == -1 ? 6379 : u.getPort();
                String user = null;
                String password = null;
                String userInfo = u.getUserInfo();
                if (userInfo != null && !userInfo.isEmpty()) {
                    int colon = userInfo.indexOf(':');
                    if (colon >= 0) {
                        user = colon == 0 ? null : userInfo.substring(0, colon);
                        password = userInfo.substring(colon + 1);
                    } else {
                        password = userInfo;
                    }
                }
                return new Endpoint(host, port, user, password, tls);
            } catch (IllegalArgumentException e) {
                throw new RespException("invalid Redis URI: " + uri, e);
            }
        }
    }
}
