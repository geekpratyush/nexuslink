package com.nexuslink.protocol.db;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, dependency-free parser and builder for the common JDBC URL shapes. It breaks a URL
 * into its components (subprotocol, host, port, database/service and a properties map) and
 * rebuilds the canonical form via {@link #toString()} — useful for validating connection
 * strings, filling in vendor default ports and normalising them before handing them to
 * {@link JdbcService}.
 *
 * <p>Recognised shapes (canonical forms round-trip through {@code parse(u).toString()}):
 * <ul>
 *   <li>PostgreSQL: {@code jdbc:postgresql://host:port/db?k=v&k2=v2} (default port 5432)</li>
 *   <li>MySQL: {@code jdbc:mysql://host:port/db?...} (default 3306)</li>
 *   <li>MariaDB: {@code jdbc:mariadb://host:port/db?...} (default 3306)</li>
 *   <li>SQL Server: {@code jdbc:sqlserver://host:port;databaseName=db;key=val} (default 1433;
 *       {@code ;}-separated properties, database carried in {@code databaseName})</li>
 *   <li>Oracle thin, modern: {@code jdbc:oracle:thin:@//host:port/service} (default 1521)</li>
 *   <li>Oracle thin, legacy: {@code jdbc:oracle:thin:@host:port:sid} (default 1521)</li>
 *   <li>SQLite: {@code jdbc:sqlite:/path/to/file.db} (file path; no host/port)</li>
 * </ul>
 *
 * @param vendor      the recognised database vendor
 * @param subprotocol the JDBC subprotocol as written (e.g. {@code postgresql}, {@code oracle:thin})
 * @param host        the server host, or {@code null} for SQLite
 * @param port        the port (vendor default filled when omitted), or {@code -1} for SQLite
 * @param database    the database name / Oracle service / Oracle SID / SQLite file path; may be {@code null}
 * @param params      extra connection properties, insertion-ordered and unmodifiable
 * @param oracleForm  which Oracle thin shape was used, or {@code null} for non-Oracle URLs
 */
public record JdbcUrl(
        Vendor vendor,
        String subprotocol,
        String host,
        int port,
        String database,
        Map<String, String> params,
        OracleForm oracleForm) {

    /** Database vendors understood by the parser, each with its subprotocol and default port. */
    public enum Vendor {
        POSTGRESQL("postgresql", 5432),
        MYSQL("mysql", 3306),
        MARIADB("mariadb", 3306),
        SQLSERVER("sqlserver", 1433),
        ORACLE("oracle:thin", 1521),
        SQLITE("sqlite", -1);

        private final String subprotocol;
        private final int defaultPort;

        Vendor(String subprotocol, int defaultPort) {
            this.subprotocol = subprotocol;
            this.defaultPort = defaultPort;
        }

        public String subprotocol() {
            return subprotocol;
        }

        /** The vendor's default port, or {@code -1} for file-based vendors (SQLite). */
        public int defaultPort() {
            return defaultPort;
        }
    }

    /** The two Oracle thin URL shapes; {@code database} carries the service name or SID accordingly. */
    public enum OracleForm {
        /** Modern EZConnect: {@code @//host:port/service}. */
        SERVICE,
        /** Legacy: {@code @host:port:sid}. */
        SID
    }

    /** Raised when a URL is not a recognised or well-formed JDBC URL. */
    public static final class JdbcUrlException extends IllegalArgumentException {
        public JdbcUrlException(String message) {
            super(message);
        }
    }

    public JdbcUrl {
        Objects.requireNonNull(vendor, "vendor");
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** True when this URL has a network port (i.e. not a file-based vendor such as SQLite). */
    public boolean hasPort() {
        return port >= 0;
    }

    /**
     * Parses a JDBC URL into its components, filling in the vendor default port when omitted.
     *
     * @throws JdbcUrlException if the URL is null, blank, uses an unknown subprotocol or is malformed
     */
    public static JdbcUrl parse(String url) {
        if (url == null || url.isBlank()) {
            throw new JdbcUrlException("JDBC URL is null or blank");
        }
        String u = url.trim();
        if (!u.startsWith("jdbc:")) {
            throw new JdbcUrlException("Not a JDBC URL (missing 'jdbc:' prefix): " + url);
        }
        if (startsWithIgnoreCase(u, "jdbc:postgresql://")) {
            return parseHostStyle(Vendor.POSTGRESQL, u, "jdbc:postgresql://");
        }
        if (startsWithIgnoreCase(u, "jdbc:mysql://")) {
            return parseHostStyle(Vendor.MYSQL, u, "jdbc:mysql://");
        }
        if (startsWithIgnoreCase(u, "jdbc:mariadb://")) {
            return parseHostStyle(Vendor.MARIADB, u, "jdbc:mariadb://");
        }
        if (startsWithIgnoreCase(u, "jdbc:sqlserver://")) {
            return parseSqlServer(u);
        }
        if (startsWithIgnoreCase(u, "jdbc:oracle:thin:")) {
            return parseOracle(u);
        }
        if (startsWithIgnoreCase(u, "jdbc:sqlite:")) {
            String path = u.substring("jdbc:sqlite:".length());
            return new JdbcUrl(Vendor.SQLITE, Vendor.SQLITE.subprotocol(),
                    null, -1, path.isEmpty() ? null : path, Map.of(), null);
        }
        throw new JdbcUrlException("Unrecognised JDBC subprotocol: " + url);
    }

    // ---- PostgreSQL / MySQL / MariaDB: //host:port/db?k=v&k2=v2 ----
    private static JdbcUrl parseHostStyle(Vendor vendor, String url, String prefix) {
        String rest = url.substring(prefix.length());

        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }

        String authority = rest;
        String database = null;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            String db = rest.substring(slash + 1);
            database = db.isEmpty() ? null : db;
        }

        HostPort hp = parseHostPort(authority, vendor);
        Map<String, String> params = parseAmpParams(query);
        return new JdbcUrl(vendor, vendor.subprotocol(), hp.host, hp.port, database, params, null);
    }

    // ---- SQL Server: //host:port;databaseName=db;key=val ----
    private static JdbcUrl parseSqlServer(String url) {
        String rest = url.substring("jdbc:sqlserver://".length());
        String[] tokens = rest.split(";", -1);

        String authority = tokens[0];
        HostPort hp = parseHostPort(authority, Vendor.SQLSERVER);

        String database = null;
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.isEmpty()) {
                continue;
            }
            int eq = tok.indexOf('=');
            String key = eq >= 0 ? tok.substring(0, eq) : tok;
            String val = eq >= 0 ? tok.substring(eq + 1) : "";
            if (key.equalsIgnoreCase("databaseName")) {
                database = val;
            } else {
                params.put(key, val);
            }
        }
        return new JdbcUrl(Vendor.SQLSERVER, Vendor.SQLSERVER.subprotocol(),
                hp.host, hp.port, database, params, null);
    }

    // ---- Oracle thin: @//host:port/service (modern) or @host:port:sid (legacy) ----
    private static JdbcUrl parseOracle(String url) {
        String rest = url.substring("jdbc:oracle:thin:".length());
        if (!rest.startsWith("@")) {
            throw new JdbcUrlException("Oracle thin URL must contain '@': " + url);
        }
        rest = rest.substring(1);

        if (rest.startsWith("//")) {
            // Modern EZConnect: //host:port/service
            String body = rest.substring(2);
            String authority = body;
            String service = null;
            int slash = body.indexOf('/');
            if (slash >= 0) {
                authority = body.substring(0, slash);
                String s = body.substring(slash + 1);
                service = s.isEmpty() ? null : s;
            }
            HostPort hp = parseHostPort(authority, Vendor.ORACLE);
            return new JdbcUrl(Vendor.ORACLE, Vendor.ORACLE.subprotocol(),
                    hp.host, hp.port, service, Map.of(), OracleForm.SERVICE);
        }

        // Legacy: host:port:sid
        String[] parts = rest.split(":", -1);
        String host = parts[0];
        if (host.isEmpty()) {
            throw new JdbcUrlException("Oracle thin URL is missing a host: " + url);
        }
        int port = Vendor.ORACLE.defaultPort();
        String sid = null;
        if (parts.length >= 3) {
            port = parsePort(parts[1], url);
            String s = parts[2];
            sid = s.isEmpty() ? null : s;
        } else if (parts.length == 2) {
            port = parsePort(parts[1], url);
        }
        return new JdbcUrl(Vendor.ORACLE, Vendor.ORACLE.subprotocol(),
                host, port, sid, Map.of(), OracleForm.SID);
    }

    // ---- helpers ----

    private record HostPort(String host, int port) {}

    private static HostPort parseHostPort(String authority, Vendor vendor) {
        if (authority == null || authority.isEmpty()) {
            throw new JdbcUrlException("Missing host in JDBC URL for " + vendor);
        }
        String host;
        String portText = null;
        if (authority.startsWith("[")) {
            // Bracketed IPv6 literal: [::1]:5432
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new JdbcUrlException("Unterminated IPv6 host: " + authority);
            }
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (after.startsWith(":")) {
                portText = after.substring(1);
            } else if (!after.isEmpty()) {
                throw new JdbcUrlException("Malformed host authority: " + authority);
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                portText = authority.substring(colon + 1);
            } else {
                host = authority;
            }
        }
        if (host.isEmpty()) {
            throw new JdbcUrlException("Empty host in JDBC URL for " + vendor);
        }
        int port = (portText == null || portText.isEmpty())
                ? vendor.defaultPort()
                : parsePort(portText, authority);
        return new HostPort(host, port);
    }

    private static int parsePort(String text, String context) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port < 0 || port > 65535) {
                throw new JdbcUrlException("Port out of range in: " + context);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new JdbcUrlException("Invalid port '" + text + "' in: " + context);
        }
    }

    private static Map<String, String> parseAmpParams(String query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String val = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(key, val);
        }
        return params;
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** Rebuilds the canonical JDBC URL string for these components. */
    @Override
    public String toString() {
        return switch (vendor) {
            case POSTGRESQL, MYSQL, MARIADB -> {
                StringBuilder sb = new StringBuilder("jdbc:")
                        .append(vendor.subprotocol()).append("://").append(host);
                if (port >= 0) {
                    sb.append(':').append(port);
                }
                if (database != null) {
                    sb.append('/').append(database);
                } else if (!params.isEmpty()) {
                    sb.append('/');
                }
                if (!params.isEmpty()) {
                    sb.append('?');
                    boolean first = true;
                    for (Map.Entry<String, String> e : params.entrySet()) {
                        if (!first) {
                            sb.append('&');
                        }
                        sb.append(e.getKey()).append('=').append(e.getValue());
                        first = false;
                    }
                }
                yield sb.toString();
            }
            case SQLSERVER -> {
                StringBuilder sb = new StringBuilder("jdbc:sqlserver://").append(host);
                if (port >= 0) {
                    sb.append(':').append(port);
                }
                if (database != null) {
                    sb.append(";databaseName=").append(database);
                }
                for (Map.Entry<String, String> e : params.entrySet()) {
                    sb.append(';').append(e.getKey()).append('=').append(e.getValue());
                }
                yield sb.toString();
            }
            case ORACLE -> {
                StringBuilder sb = new StringBuilder("jdbc:oracle:thin:@");
                if (oracleForm == OracleForm.SID) {
                    sb.append(host);
                    if (port >= 0) {
                        sb.append(':').append(port);
                    }
                    if (database != null) {
                        sb.append(':').append(database);
                    }
                } else {
                    sb.append("//").append(host);
                    if (port >= 0) {
                        sb.append(':').append(port);
                    }
                    sb.append('/');
                    if (database != null) {
                        sb.append(database);
                    }
                }
                yield sb.toString();
            }
            case SQLITE -> "jdbc:sqlite:" + (database == null ? "" : database);
        };
    }
}
