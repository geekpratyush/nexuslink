package com.nexuslink.protocol.mongo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure, dependency-free parser for a MongoDB connection string (URI), following the
 * <a href="https://www.mongodb.com/docs/manual/reference/connection-string/">connection-string spec</a>.
 *
 * <p>This deliberately does <em>not</em> use the MongoDB driver: it is plain string parsing so it
 * can be exercised entirely offline. It also never performs SRV DNS resolution — a
 * {@code mongodb+srv://} URI is parsed and flagged, but the SRV/TXT lookup is left to the caller.
 *
 * <p>Grammar handled:
 * <pre>
 *   mongodb://[username:password@]host1[:port1][,host2[:port2],...][/[defaultauthdb]][?opt=val&amp;...]
 *   mongodb+srv://[username:password@]host[/[defaultauthdb]][?opt=val&amp;...]
 * </pre>
 * Userinfo, the default auth database, and option keys/values are percent-decoded. Option keys are
 * case-insensitive per the spec and are normalized to lower-case.
 */
public final class MongoConnectionString {

    /** Standard scheme; hosts may each carry a port and default to {@link #DEFAULT_PORT}. */
    public static final String SCHEME = "mongodb://";
    /** Seedlist (SRV) scheme; exactly one host, no port. */
    public static final String SRV_SCHEME = "mongodb+srv://";
    /** Default MongoDB port applied to {@code mongodb://} hosts that omit one. */
    public static final int DEFAULT_PORT = 27017;

    private final boolean srv;
    private final List<String> hosts;
    private final String username;
    private final String password;
    private final String authDatabase;
    private final Map<String, String> options;

    private MongoConnectionString(boolean srv,
                                  List<String> hosts,
                                  String username,
                                  String password,
                                  String authDatabase,
                                  Map<String, String> options) {
        this.srv = srv;
        this.hosts = Collections.unmodifiableList(hosts);
        this.username = username;
        this.password = password;
        this.authDatabase = authDatabase;
        this.options = Collections.unmodifiableMap(options);
    }

    /**
     * Parses a MongoDB connection string.
     *
     * @throws MongoConnectionStringException if the URI is malformed
     */
    public static MongoConnectionString parse(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new MongoConnectionStringException("Connection string is empty");
        }

        boolean srv;
        String remainder;
        if (uri.startsWith(SRV_SCHEME)) {
            srv = true;
            remainder = uri.substring(SRV_SCHEME.length());
        } else if (uri.startsWith(SCHEME)) {
            srv = false;
            remainder = uri.substring(SCHEME.length());
        } else {
            throw new MongoConnectionStringException(
                    "Connection string must start with \"" + SCHEME + "\" or \"" + SRV_SCHEME + "\"");
        }

        // Split off the "?options" query string.
        String main = remainder;
        String optionString = null;
        int q = remainder.indexOf('?');
        if (q >= 0) {
            main = remainder.substring(0, q);
            optionString = remainder.substring(q + 1);
        }

        // Split off "[username:password@]".
        String username = null;
        String password = null;
        int at = main.indexOf('@');
        if (at >= 0) {
            String userInfo = main.substring(0, at);
            main = main.substring(at + 1);
            if (userInfo.isEmpty()) {
                throw new MongoConnectionStringException("Empty userinfo before '@'");
            }
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                username = percentDecode(userInfo.substring(0, colon));
                password = percentDecode(userInfo.substring(colon + 1));
            } else {
                username = percentDecode(userInfo);
            }
            if (username.isEmpty()) {
                throw new MongoConnectionStringException("Empty username in userinfo");
            }
        }

        // Split off "/[defaultauthdb]".
        String hostSection = main;
        String authDatabase = null;
        int slash = main.indexOf('/');
        if (slash >= 0) {
            hostSection = main.substring(0, slash);
            String db = main.substring(slash + 1);
            if (!db.isEmpty()) {
                authDatabase = percentDecode(db);
            }
        }

        List<String> hosts = parseHosts(hostSection, srv);
        Map<String, String> options = parseOptions(optionString);

        return new MongoConnectionString(srv, hosts, username, password, authDatabase, options);
    }

    private static List<String> parseHosts(String hostSection, boolean srv) {
        if (hostSection.isEmpty()) {
            throw new MongoConnectionStringException("Connection string has no host");
        }
        String[] raw = hostSection.split(",", -1);
        if (srv && raw.length > 1) {
            throw new MongoConnectionStringException(
                    SRV_SCHEME + " must contain exactly one host, but found " + raw.length);
        }
        List<String> hosts = new ArrayList<>(raw.length);
        for (String h : raw) {
            hosts.add(normalizeHost(h, srv));
        }
        return hosts;
    }

    private static String normalizeHost(String host, boolean srv) {
        if (host.isEmpty()) {
            throw new MongoConnectionStringException("Empty host in host list");
        }
        String hostname;
        String portPart;
        if (host.startsWith("[")) {
            // IPv6 literal, e.g. [::1] or [::1]:27017
            int close = host.indexOf(']');
            if (close < 0) {
                throw new MongoConnectionStringException("Unterminated IPv6 host: " + host);
            }
            hostname = host.substring(0, close + 1);
            String rest = host.substring(close + 1);
            if (rest.isEmpty()) {
                portPart = null;
            } else if (rest.startsWith(":")) {
                portPart = rest.substring(1);
            } else {
                throw new MongoConnectionStringException("Malformed IPv6 host: " + host);
            }
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0) {
                hostname = host.substring(0, colon);
                portPart = host.substring(colon + 1);
            } else {
                hostname = host;
                portPart = null;
            }
        }

        if (hostname.isEmpty() || "[]".equals(hostname)) {
            throw new MongoConnectionStringException("Empty host in host list");
        }

        if (portPart != null) {
            if (srv) {
                throw new MongoConnectionStringException(
                        SRV_SCHEME + " host must not include a port: " + host);
            }
            int port = parsePort(portPart, host);
            return hostname + ":" + port;
        }
        return srv ? hostname : hostname + ":" + DEFAULT_PORT;
    }

    private static int parsePort(String portPart, String host) {
        if (portPart.isEmpty()) {
            throw new MongoConnectionStringException("Missing port after ':' in host: " + host);
        }
        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            throw new MongoConnectionStringException("Invalid port in host: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new MongoConnectionStringException("Port out of range (1-65535) in host: " + host);
        }
        return port;
    }

    private static Map<String, String> parseOptions(String optionString) {
        Map<String, String> options = new LinkedHashMap<>();
        if (optionString == null || optionString.isEmpty()) {
            return options;
        }
        for (String pair : optionString.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new MongoConnectionStringException("Option without '=': " + pair);
            }
            String key = percentDecode(pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            String value = percentDecode(pair.substring(eq + 1));
            if (key.isEmpty()) {
                throw new MongoConnectionStringException("Empty option name in: " + pair);
            }
            options.put(key, value);
        }
        return options;
    }

    /** Decodes {@code %XX} escapes (UTF-8). Unlike form decoding, {@code '+'} is left untouched. */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new MongoConnectionStringException("Truncated percent-escape in: " + s);
                }
                int hi = hexValue(s.charAt(i + 1), s);
                int lo = hexValue(s.charAt(i + 2), s);
                out.write((hi << 4) + lo);
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hexValue(char c, String context) {
        int v = Character.digit(c, 16);
        if (v < 0) {
            throw new MongoConnectionStringException("Invalid percent-escape in: " + context);
        }
        return v;
    }

    // ---- Accessors ----------------------------------------------------------

    /** {@code true} for a {@code mongodb+srv://} seedlist URI. */
    public boolean isSrv() {
        return srv;
    }

    /** Host entries as {@code host[:port]} (SRV hosts carry no port). Never empty. */
    public List<String> hosts() {
        return hosts;
    }

    public Optional<String> username() {
        return Optional.ofNullable(username);
    }

    public Optional<String> password() {
        return Optional.ofNullable(password);
    }

    /** The optional {@code /defaultauthdb} path segment. */
    public Optional<String> authDatabase() {
        return Optional.ofNullable(authDatabase);
    }

    /** Unmodifiable, insertion-ordered option map with normalized (lower-case) keys. */
    public Map<String, String> options() {
        return options;
    }

    /** Raw value for a case-insensitive option key, if present. */
    public Optional<String> option(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(options.get(key.toLowerCase(Locale.ROOT)));
    }

    // ---- Typed option helpers ----------------------------------------------

    /** TLS/SSL flag: honours the {@code tls} option, falling back to the legacy {@code ssl} alias. */
    public Optional<Boolean> tls() {
        Optional<Boolean> t = booleanOption("tls");
        return t.isPresent() ? t : booleanOption("ssl");
    }

    public Optional<String> replicaSet() {
        return option("replicaset");
    }

    public Optional<String> authSource() {
        return option("authsource");
    }

    public Optional<Boolean> retryWrites() {
        return booleanOption("retrywrites");
    }

    private Optional<Boolean> booleanOption(String key) {
        return option(key).map(v -> {
            String lower = v.toLowerCase(Locale.ROOT);
            if ("true".equals(lower)) {
                return Boolean.TRUE;
            }
            if ("false".equals(lower)) {
                return Boolean.FALSE;
            }
            throw new MongoConnectionStringException(
                    "Option '" + key + "' expects true/false but was: " + v);
        });
    }

    // ---- Rendering ----------------------------------------------------------

    /** Connection string with the password masked; safe for logs. */
    public String redacted() {
        StringBuilder sb = new StringBuilder(srv ? SRV_SCHEME : SCHEME);
        if (username != null) {
            sb.append(username);
            if (password != null) {
                sb.append(":****");
            }
            sb.append('@');
        }
        sb.append(String.join(",", hosts));
        sb.append('/');
        if (authDatabase != null) {
            sb.append(authDatabase);
        }
        if (!options.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> e : options.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return redacted();
    }
}
