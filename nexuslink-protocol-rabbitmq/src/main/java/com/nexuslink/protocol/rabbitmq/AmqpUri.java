package com.nexuslink.protocol.rabbitmq;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Pure, dependency-free parser for a RabbitMQ / AMQP 0-9-1 connection URI &mdash; no {@code
 * amqp-client} required, so it is fully offline-testable. It splits and percent-decodes a URI of
 * the shape:
 *
 * <pre>{@code amqp[s]://[user[:password]@]host[:port][/vhost][?k=v&k2=v2]}</pre>
 *
 * <p>Two schemes are recognised: {@code amqp} (plaintext, default port {@code 5672}) and {@code
 * amqps} (TLS, default port {@code 5671}). When the host is omitted it defaults to {@code
 * localhost}; when the port is omitted it falls back to the scheme default.
 *
 * <p>The virtual-host rules follow the AMQP URI spec and contain a classic gotcha:
 * <ul>
 *   <li>an <em>absent</em> path ({@code amqp://host}) means the <em>default</em> vhost {@code "/"};</li>
 *   <li>an <em>empty</em> path ({@code amqp://host/}) means the <em>empty-string</em> vhost {@code ""};</li>
 *   <li>the path is a single segment &mdash; a second raw {@code '/'} is an error, while an escaped
 *       {@code %2f} decodes to a literal {@code '/'} <em>inside</em> the vhost name.</li>
 * </ul>
 *
 * <p>Userinfo (username, password) and the vhost are percent-decoded as UTF-8. {@link #toString()}
 * and {@link #redacted()} both mask the password so it is never printed in the clear. Malformed
 * input throws {@link AmqpUriException}.
 */
public final class AmqpUri {

    private static final String SCHEME_AMQP = "amqp";
    private static final String SCHEME_AMQPS = "amqps";
    private static final int DEFAULT_PORT_AMQP = 5672;
    private static final int DEFAULT_PORT_AMQPS = 5671;
    /** The default virtual host used when the URI carries no path at all. */
    private static final String DEFAULT_VHOST = "/";
    private static final String REDACTED = "****";

    private final String scheme;
    private final boolean tls;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String vhost;
    private final Map<String, String> params;

    private AmqpUri(String scheme, boolean tls, String host, int port, String username,
                    String password, String vhost, Map<String, String> params) {
        this.scheme = scheme;
        this.tls = tls;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.vhost = vhost;
        this.params = params;
    }

    /**
     * Parses {@code uri} into its components. Never performs I/O.
     *
     * @throws AmqpUriException if {@code uri} is {@code null}/blank, has an unknown scheme, a
     *     non-numeric or out-of-range port, an illegal percent escape or an extra path segment.
     */
    public static AmqpUri parse(String uri) {
        if (uri == null) {
            throw new AmqpUriException("AMQP URI is null");
        }
        String trimmed = uri.trim();
        if (trimmed.isEmpty()) {
            throw new AmqpUriException("AMQP URI is blank");
        }

        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd <= 0) {
            throw new AmqpUriException("AMQP URI must start with 'amqp://' or 'amqps://': " + uri);
        }
        String scheme = trimmed.substring(0, schemeEnd).toLowerCase(java.util.Locale.ROOT);
        boolean tls;
        if (scheme.equals(SCHEME_AMQP)) {
            tls = false;
        } else if (scheme.equals(SCHEME_AMQPS)) {
            tls = true;
        } else {
            throw new AmqpUriException("Unsupported AMQP scheme '" + scheme
                    + "' (expected 'amqp' or 'amqps')");
        }

        String rest = trimmed.substring(schemeEnd + 3);

        // Split off the query string at the first '?'.
        String query = null;
        int qIndex = rest.indexOf('?');
        if (qIndex >= 0) {
            query = rest.substring(qIndex + 1);
            rest = rest.substring(0, qIndex);
        }

        // Split authority from the (optional) single-segment path at the first '/'.
        String authority;
        String vhost;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            authority = rest;                 // no path at all -> default vhost
            vhost = DEFAULT_VHOST;
        } else {
            authority = rest.substring(0, slash);
            String pathRaw = rest.substring(slash + 1);
            if (pathRaw.indexOf('/') >= 0) {
                throw new AmqpUriException("AMQP URI vhost path may not contain more than one "
                        + "segment (use %2f for a literal '/'): " + uri);
            }
            vhost = decode(pathRaw, "vhost"); // empty path -> empty-string vhost
        }

        // Authority: [user[:password]@]host[:port]
        String username = null;
        String password = null;
        int at = authority.lastIndexOf('@');
        String hostPort = authority;
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            hostPort = authority.substring(at + 1);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = decode(userInfo.substring(0, colon), "username");
                password = decode(userInfo.substring(colon + 1), "password");
            } else {
                username = decode(userInfo, "username");
            }
        }

        String host;
        Integer explicitPort;
        if (hostPort.startsWith("[")) {
            // IPv6 literal, e.g. [::1] or [::1]:5671
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw new AmqpUriException("Unterminated IPv6 host literal: " + uri);
            }
            host = hostPort.substring(1, close);
            explicitPort = parsePortSuffix(hostPort.substring(close + 1), uri);
        } else {
            int colon = hostPort.indexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                explicitPort = parsePort(hostPort.substring(colon + 1), uri);
            } else {
                host = hostPort;
                explicitPort = null;
            }
        }
        if (host.isEmpty()) {
            host = "localhost";
        }
        int port = explicitPort != null ? explicitPort : (tls ? DEFAULT_PORT_AMQPS : DEFAULT_PORT_AMQP);

        Map<String, String> params = parseQuery(query);

        return new AmqpUri(scheme, tls, host, port, username, password, vhost, params);
    }

    /** Parses the part after a {@code ']'} in an IPv6 authority, which is either empty or {@code :port}. */
    private static Integer parsePortSuffix(String suffix, String uri) {
        if (suffix.isEmpty()) {
            return null;
        }
        if (suffix.charAt(0) != ':') {
            throw new AmqpUriException("Unexpected characters after IPv6 host literal: " + uri);
        }
        return parsePort(suffix.substring(1), uri);
    }

    private static Integer parsePort(String portText, String uri) {
        if (portText.isEmpty()) {
            return null; // e.g. "amqp://host:/vhost" -> fall back to scheme default
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new AmqpUriException("Invalid AMQP port '" + portText + "' in URI: " + uri, e);
        }
        if (port < 1 || port > 65535) {
            throw new AmqpUriException("AMQP port out of range (1-65535): " + port);
        }
        return port;
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key;
            String value;
            if (eq >= 0) {
                key = decode(pair.substring(0, eq), "query key");
                value = decode(pair.substring(eq + 1), "query value");
            } else {
                key = decode(pair, "query key");
                value = "";
            }
            params.put(key, value);
        }
        return Collections.unmodifiableMap(params);
    }

    /** Percent-decodes a URI component as UTF-8. {@code '+'} is left untouched (AMQP is not form-encoded). */
    private static String decode(String s, String what) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new AmqpUriException("Truncated percent escape in " + what + ": " + s);
                }
                int hi = hexValue(s.charAt(i + 1), what, s);
                int lo = hexValue(s.charAt(i + 2), what, s);
                out.write((hi << 4) + lo);
                i += 3;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hexValue(char c, String what, String s) {
        int v = Character.digit(c, 16);
        if (v < 0) {
            throw new AmqpUriException("Illegal percent escape in " + what + ": " + s);
        }
        return v;
    }

    /** The URI scheme, either {@code "amqp"} or {@code "amqps"}. */
    public String scheme() {
        return scheme;
    }

    /** {@code true} when the scheme is {@code amqps} (TLS). */
    public boolean tls() {
        return tls;
    }

    /** The host, defaulting to {@code localhost} when absent from the URI. */
    public String host() {
        return host;
    }

    /** The resolved port &mdash; the explicit URI port, or the scheme default when omitted. */
    public int port() {
        return port;
    }

    /** The decoded username, or {@code null} when the URI carried no userinfo. */
    public String username() {
        return username;
    }

    /** The decoded password, or {@code null} when the URI carried none. */
    public String password() {
        return password;
    }

    /** The decoded virtual host (see the class doc for the absent-vs-empty distinction). */
    public String vhost() {
        return vhost;
    }

    /** The decoded, insertion-ordered query parameters (unmodifiable, possibly empty). */
    public Map<String, String> params() {
        return params;
    }

    /** The raw string value of query parameter {@code name}, or {@code null} when absent. */
    public String param(String name) {
        return params.get(name);
    }

    /**
     * Query parameter {@code name} parsed as an {@code int}, or an empty {@link OptionalInt} when the
     * parameter is absent.
     *
     * @throws AmqpUriException if the value is present but not a valid integer.
     */
    public OptionalInt intParam(String name) {
        String value = params.get(name);
        if (value == null || value.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new AmqpUriException("Query parameter '" + name + "' is not an integer: " + value, e);
        }
    }

    /** The {@code heartbeat} query parameter (seconds) as an int, if present. */
    public OptionalInt heartbeat() {
        return intParam("heartbeat");
    }

    /** The {@code connection_timeout} query parameter (milliseconds) as an int, if present. */
    public OptionalInt connectionTimeout() {
        return intParam("connection_timeout");
    }

    /** The {@code channel_max} query parameter as an int, if present. */
    public OptionalInt channelMax() {
        return intParam("channel_max");
    }

    /** A canonical URI string with the password masked; safe to log. */
    public String redacted() {
        return build(true);
    }

    /** Equivalent to {@link #redacted()}: the password is always masked. */
    @Override
    public String toString() {
        return build(true);
    }

    private String build(boolean maskPassword) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (username != null) {
            sb.append(encode(username));
            if (password != null) {
                sb.append(':').append(maskPassword ? REDACTED : encode(password));
            }
            sb.append('@');
        }
        if (host.indexOf(':') >= 0) {
            sb.append('[').append(host).append(']'); // IPv6 literal
        } else {
            sb.append(host);
        }
        sb.append(':').append(port);
        sb.append('/').append(encodeVhost(vhost));
        if (!params.isEmpty()) {
            char sep = '?';
            for (Map.Entry<String, String> e : params.entrySet()) {
                sb.append(sep).append(encode(e.getKey())).append('=').append(encode(e.getValue()));
                sep = '&';
            }
        }
        return sb.toString();
    }

    /** Percent-encodes a component, escaping everything outside the RFC 3986 unreserved set. */
    private static String encode(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** Like {@link #encode} but used for the single vhost segment, where {@code '/'} becomes {@code %2F}. */
    private static String encodeVhost(String s) {
        return encode(s);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AmqpUri other)) {
            return false;
        }
        return tls == other.tls
                && port == other.port
                && scheme.equals(other.scheme)
                && host.equals(other.host)
                && Objects.equals(username, other.username)
                && Objects.equals(password, other.password)
                && vhost.equals(other.vhost)
                && params.equals(other.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, tls, host, port, username, password, vhost, params);
    }
}
