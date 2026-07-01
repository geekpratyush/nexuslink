package com.nexuslink.protocol.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A pure, dependency-free parser for the broker connection URIs an MQTT client connects with, of the
 * general form {@code scheme://[user[:password]@]host[:port][/path]}. It mirrors the URI forms
 * Eclipse Paho accepts in {@link MqttService#connect} without pulling in any networking dependency,
 * so it is fully offline-testable.
 *
 * <p>Recognised schemes, their transport flags and default ports:
 * <ul>
 *   <li>{@code tcp://} and its alias {@code mqtt://} — plain TCP, port {@code 1883}.</li>
 *   <li>{@code ssl://}, {@code tls://}, {@code mqtts://} — TLS over TCP, port {@code 8883}
 *       ({@link #tls()} is {@code true}).</li>
 *   <li>{@code ws://} — MQTT over WebSocket, port {@code 80} ({@link #websocket()} is {@code true}).</li>
 *   <li>{@code wss://} — MQTT over secure WebSocket, port {@code 443} (both {@link #tls()} and
 *       {@link #websocket()} are {@code true}).</li>
 * </ul>
 *
 * <p>Scheme matching is case-insensitive and the canonical (lower-case) scheme is always stored. The
 * <em>path</em> component only applies to the WebSocket schemes: when omitted it defaults to
 * {@value #DEFAULT_WEBSOCKET_PATH}, and for the non-WebSocket schemes it is always {@code null}. Any
 * {@code user:password@} userinfo is percent-decoded on parse; {@link #normalized()} percent-encodes
 * it back so a normalized URI round-trips through {@link #parse(String)}.
 *
 * <p>Instances are immutable. Malformed input (unknown scheme, missing host, out-of-range or
 * non-numeric port, bad percent-encoding) raises {@link MqttBrokerUriException}.
 */
public final class MqttBrokerUri {

    /** Default path used for the WebSocket schemes when the URI omits one. */
    public static final String DEFAULT_WEBSOCKET_PATH = "/mqtt";

    /** The lowest and highest valid TCP port numbers. */
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    private final String scheme;
    private final boolean tls;
    private final boolean websocket;
    private final String host;
    private final int port;
    private final String path;      // null for non-WebSocket schemes
    private final String username;  // null when no userinfo present
    private final String password;  // null when no password present

    private MqttBrokerUri(String scheme, boolean tls, boolean websocket, String host, int port,
                          String path, String username, String password) {
        this.scheme = scheme;
        this.tls = tls;
        this.websocket = websocket;
        this.host = host;
        this.port = port;
        this.path = path;
        this.username = username;
        this.password = password;
    }

    /**
     * Parses {@code uri} into an {@link MqttBrokerUri}.
     *
     * @throws MqttBrokerUriException if {@code uri} is {@code null}, blank, or malformed
     */
    public static MqttBrokerUri parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new MqttBrokerUriException("Broker URI must not be null or blank");
        }
        String trimmed = uri.trim();

        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) {
            throw new MqttBrokerUriException(
                    "Broker URI must contain a scheme separator \"://\": " + quote(uri));
        }
        String rawScheme = trimmed.substring(0, schemeEnd);
        Scheme scheme = Scheme.of(rawScheme);
        if (scheme == null) {
            throw new MqttBrokerUriException("Unknown MQTT broker URI scheme: " + quote(rawScheme));
        }

        String rest = trimmed.substring(schemeEnd + 3);

        // Split authority from path at the first '/'.
        String authority;
        String rawPath;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            authority = rest;
            rawPath = null;
        } else {
            authority = rest.substring(0, slash);
            rawPath = rest.substring(slash); // includes leading '/'
        }

        // Optional userinfo, ending at the last '@' before the host/port.
        String username = null;
        String password = null;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String userinfo = authority.substring(0, at);
            authority = authority.substring(at + 1);
            int colon = userinfo.indexOf(':');
            if (colon < 0) {
                username = percentDecode(userinfo);
            } else {
                username = percentDecode(userinfo.substring(0, colon));
                password = percentDecode(userinfo.substring(colon + 1));
            }
            if (username.isEmpty()) {
                throw new MqttBrokerUriException("Broker URI userinfo has an empty username: " + quote(uri));
            }
        }

        HostPort hostPort = parseHostPort(authority, uri);
        int port = (hostPort.port < 0) ? scheme.defaultPort : hostPort.port;

        // Path applies only to the WebSocket schemes.
        String path;
        if (scheme.websocket) {
            path = (rawPath == null || rawPath.equals("/")) ? DEFAULT_WEBSOCKET_PATH : rawPath;
        } else {
            if (rawPath != null && !rawPath.equals("/")) {
                throw new MqttBrokerUriException(
                        "Scheme " + quote(scheme.canonical) + " does not accept a path: " + quote(uri));
            }
            path = null;
        }

        return new MqttBrokerUri(scheme.canonical, scheme.tls, scheme.websocket,
                hostPort.host, port, path, username, password);
    }

    private static HostPort parseHostPort(String authority, String original) {
        String host;
        int port;
        if (authority.startsWith("[")) {
            // Bracketed IPv6 literal: [::1] or [::1]:1883.
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new MqttBrokerUriException(
                        "Broker URI has an unclosed IPv6 host literal: " + quote(original));
            }
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (after.isEmpty()) {
                port = -1;
            } else if (after.charAt(0) == ':') {
                port = parsePort(after.substring(1), original);
            } else {
                throw new MqttBrokerUriException(
                        "Unexpected characters after IPv6 host literal: " + quote(original));
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon < 0) {
                host = authority;
                port = -1;
            } else {
                if (authority.indexOf(':', colon + 1) >= 0) {
                    throw new MqttBrokerUriException(
                            "Broker URI host has multiple ':' (bracket IPv6 literals): " + quote(original));
                }
                host = authority.substring(0, colon);
                port = parsePort(authority.substring(colon + 1), original);
            }
        }
        if (host.isEmpty()) {
            throw new MqttBrokerUriException("Broker URI is missing a host: " + quote(original));
        }
        return new HostPort(host, port);
    }

    private static int parsePort(String raw, String original) {
        if (raw.isEmpty()) {
            throw new MqttBrokerUriException("Broker URI has an empty port: " + quote(original));
        }
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new MqttBrokerUriException("Broker URI port is not a number: " + quote(raw));
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new MqttBrokerUriException(
                    "Broker URI port out of range [" + MIN_PORT + ".." + MAX_PORT + "]: " + port);
        }
        return port;
    }

    // --- accessors -------------------------------------------------------------------------------

    /** The canonical (lower-case) scheme, e.g. {@code tcp}, {@code ssl}, {@code ws}, {@code wss}. */
    public String scheme() {
        return scheme;
    }

    /** {@code true} for the TLS schemes ({@code ssl}/{@code tls}/{@code mqtts}/{@code wss}). */
    public boolean tls() {
        return tls;
    }

    /** {@code true} for the WebSocket schemes ({@code ws}/{@code wss}). */
    public boolean websocket() {
        return websocket;
    }

    /** The host (never {@code null} or empty). */
    public String host() {
        return host;
    }

    /** The resolved port: the URI's explicit port, or the scheme default when absent. */
    public int port() {
        return port;
    }

    /**
     * The WebSocket path (with leading {@code /}); {@code null} for the non-WebSocket schemes and
     * {@value #DEFAULT_WEBSOCKET_PATH} when a WebSocket URI omits one.
     */
    public String path() {
        return path;
    }

    /** The percent-decoded username, or {@code null} when the URI carries no userinfo. */
    public String username() {
        return username;
    }

    /** The percent-decoded password, or {@code null} when none was supplied. */
    public String password() {
        return password;
    }

    // --- rendering -------------------------------------------------------------------------------

    /**
     * Rebuilds the canonical URI: lower-case scheme, percent-encoded userinfo (when present), host,
     * the resolved port (always rendered) and the WebSocket path. The result round-trips through
     * {@link #parse(String)}.
     */
    public String normalized() {
        return render(false);
    }

    /** Like {@link #normalized()} but masks the password (when present) as {@code ***}. */
    public String redacted() {
        return render(true);
    }

    private String render(boolean maskPassword) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (username != null) {
            sb.append(percentEncode(username));
            if (password != null) {
                sb.append(':').append(maskPassword ? "***" : percentEncode(password));
            }
            sb.append('@');
        }
        boolean ipv6 = host.indexOf(':') >= 0;
        if (ipv6) {
            sb.append('[').append(host).append(']');
        } else {
            sb.append(host);
        }
        sb.append(':').append(port);
        if (path != null) {
            sb.append(path);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return normalized();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MqttBrokerUri other)) {
            return false;
        }
        return tls == other.tls
                && websocket == other.websocket
                && port == other.port
                && scheme.equals(other.scheme)
                && host.equals(other.host)
                && Objects.equals(path, other.path)
                && Objects.equals(username, other.username)
                && Objects.equals(password, other.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, tls, websocket, host, port, path, username, password);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String quote(String s) {
        return s == null ? "null" : '"' + s + '"';
    }

    /** Percent-decodes a userinfo component (UTF-8). */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        byte[] out = new byte[s.length()];
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new MqttBrokerUriException("Truncated percent-encoding in userinfo: " + quote(s));
                }
                int hi = hexValue(s.charAt(i + 1));
                int lo = hexValue(s.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new MqttBrokerUriException("Invalid percent-encoding in userinfo: " + quote(s));
                }
                out[len++] = (byte) ((hi << 4) | lo);
                i += 2;
            } else {
                out[len++] = (byte) c;
            }
        }
        return new String(out, 0, len, StandardCharsets.UTF_8);
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Percent-encodes everything outside the unreserved set so userinfo re-parses unambiguously. */
    private static String percentEncode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (isUnreserved(v)) {
                sb.append((char) v);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((v >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** RFC 3986 unreserved characters: ALPHA / DIGIT / '-' / '.' / '_' / '~'. */
    private static boolean isUnreserved(int v) {
        return (v >= 'A' && v <= 'Z')
                || (v >= 'a' && v <= 'z')
                || (v >= '0' && v <= '9')
                || v == '-' || v == '.' || v == '_' || v == '~';
    }

    private record HostPort(String host, int port) {}

    /** The recognised broker schemes with their transport flags and default ports. */
    private enum Scheme {
        TCP("tcp", false, false, 1883),
        MQTT("mqtt", "tcp", false, false, 1883),
        SSL("ssl", true, false, 8883),
        TLS("tls", "ssl", true, false, 8883),
        MQTTS("mqtts", "ssl", true, false, 8883),
        WS("ws", false, true, 80),
        WSS("wss", true, true, 443);

        private final String token;
        private final String canonical;
        private final boolean tls;
        private final boolean websocket;
        private final int defaultPort;

        Scheme(String token, boolean tls, boolean websocket, int defaultPort) {
            this(token, token, tls, websocket, defaultPort);
        }

        Scheme(String token, String canonical, boolean tls, boolean websocket, int defaultPort) {
            this.token = token;
            this.canonical = canonical;
            this.tls = tls;
            this.websocket = websocket;
            this.defaultPort = defaultPort;
        }

        static Scheme of(String raw) {
            if (raw == null) {
                return null;
            }
            String lower = raw.toLowerCase(java.util.Locale.ROOT);
            for (Scheme s : values()) {
                if (s.token.equals(lower)) {
                    return s;
                }
            }
            return null;
        }
    }
}
