package com.nexuslink.protocol.http.rest;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An in-memory cookie store that mimics a browser's handling of
 * {@code Set-Cookie} response headers and the matching {@code Cookie}
 * request header, following the spirit of RFC 6265.
 * <p>
 * The jar is intentionally dependency-free and fully unit-testable: the
 * expiry clock is injectable so tests can exercise expired cookies without
 * sleeping. Thread-safety is not a goal — a jar is owned by a single REST
 * session and mutated from one thread at a time.
 */
public final class CookieJar {

    /** Date formats accepted in the {@code Expires} attribute, most common first. */
    private static final DateTimeFormatter[] EXPIRES_FORMATS = {
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    };

    private final Clock clock;
    private final List<Cookie> cookies = new ArrayList<>();

    /** Creates a jar driven by the system UTC clock. */
    public CookieJar() {
        this(Clock.systemUTC());
    }

    /** Creates a jar with an explicit clock — primarily for deterministic tests. */
    public CookieJar(Clock clock) {
        this.clock = clock;
    }

    /**
     * Parses and stores every {@code Set-Cookie} header returned for a request to
     * {@code requestUri}. Malformed headers are skipped silently. A cookie whose
     * {@code Max-Age}/{@code Expires} is already in the past removes any stored
     * cookie with the same identity (name, domain, path) instead of being stored.
     */
    public void storeFrom(URI requestUri, List<String> setCookieHeaders) {
        if (requestUri == null || setCookieHeaders == null) return;
        for (String header : setCookieHeaders) {
            Cookie c = parse(header, requestUri);
            if (c != null) store(c);
        }
    }

    /**
     * Builds the {@code Cookie} request header value for {@code requestUri}, or
     * {@code null} when no stored cookie applies. Expired cookies are pruned as a
     * side effect; {@code Secure} cookies are only emitted over {@code https}.
     */
    public String cookieHeaderFor(URI requestUri) {
        if (requestUri == null || requestUri.getHost() == null) return null;
        purgeExpired();
        String host = requestUri.getHost().toLowerCase(Locale.ROOT);
        String path = normalisePath(requestUri.getPath());
        boolean secureChannel = "https".equalsIgnoreCase(requestUri.getScheme());

        List<Cookie> matches = new ArrayList<>();
        for (Cookie c : cookies) {
            if (c.secure && !secureChannel) continue;
            if (!c.domainMatches(host)) continue;
            if (!c.pathMatches(path)) continue;
            matches.add(c);
        }
        if (matches.isEmpty()) return null;

        // RFC 6265 §5.4: longer paths first; stable for equal paths.
        matches.sort((a, b) -> Integer.compare(b.path.length(), a.path.length()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) sb.append("; ");
            Cookie c = matches.get(i);
            sb.append(c.name).append('=').append(c.value);
        }
        return sb.toString();
    }

    /** Removes every stored cookie. */
    public void clear() {
        cookies.clear();
    }

    /** An immutable snapshot of the currently stored cookies (expired ones pruned). */
    public List<Cookie> all() {
        purgeExpired();
        return List.copyOf(cookies);
    }

    // ---- internals -------------------------------------------------------

    private void store(Cookie incoming) {
        // Replace any existing cookie with the same identity.
        cookies.removeIf(c -> c.sameIdentity(incoming));
        if (incoming.isExpiredAt(clock.instant())) {
            return; // an immediate-expiry header just deletes; nothing to keep.
        }
        cookies.add(incoming);
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        cookies.removeIf(c -> c.isExpiredAt(now));
    }

    private Cookie parse(String header, URI requestUri) {
        if (header == null || header.isBlank()) return null;
        String[] parts = header.split(";");
        String[] nameValue = splitPair(parts[0]);
        if (nameValue == null || nameValue[0].isEmpty()) return null;

        String name = nameValue[0];
        String value = nameValue[1];
        String domain = null;
        String path = null;
        boolean secure = false;
        boolean hostOnly = true;
        Long maxAge = null;
        Instant expires = null;

        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            if (attr.isEmpty()) continue;
            String[] kv = splitPair(attr);
            String key = kv == null ? attr.toLowerCase(Locale.ROOT)
                    : kv[0].toLowerCase(Locale.ROOT);
            String av = kv == null ? "" : kv[1];
            switch (key) {
                case "domain" -> {
                    if (!av.isEmpty()) {
                        domain = av.startsWith(".") ? av.substring(1) : av;
                        domain = domain.toLowerCase(Locale.ROOT);
                        hostOnly = false;
                    }
                }
                case "path" -> {
                    if (av.startsWith("/")) path = av;
                }
                case "secure" -> secure = true;
                case "max-age" -> maxAge = parseLong(av);
                case "expires" -> expires = parseExpires(av);
                default -> { /* HttpOnly, SameSite, etc. — not needed for sending */ }
            }
        }

        String host = requestUri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (domain == null) domain = host;
        if (path == null) path = defaultPath(requestUri.getPath());

        // Max-Age takes precedence over Expires (RFC 6265 §5.3).
        Instant expiry;
        if (maxAge != null) {
            expiry = maxAge <= 0 ? Instant.EPOCH : clock.instant().plusSeconds(maxAge);
        } else {
            expiry = expires; // null => session cookie
        }

        return new Cookie(name, value, domain, path, expiry, secure, hostOnly);
    }

    private static String[] splitPair(String s) {
        if (s == null) return null;
        int eq = s.indexOf('=');
        if (eq < 0) return null;
        return new String[]{s.substring(0, eq).trim(), s.substring(eq + 1).trim()};
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseExpires(String s) {
        if (s == null || s.isBlank()) return null;
        for (DateTimeFormatter fmt : EXPIRES_FORMATS) {
            try {
                return ZonedDateTime.parse(s.trim(), fmt).toInstant();
            } catch (RuntimeException ignored) {
                // try the next format
            }
        }
        return null;
    }

    /** RFC 6265 §5.1.4 default-path derivation from a request URI path. */
    static String defaultPath(String uriPath) {
        if (uriPath == null || uriPath.isEmpty() || !uriPath.startsWith("/")) return "/";
        int lastSlash = uriPath.lastIndexOf('/');
        if (lastSlash == 0) return "/";
        return uriPath.substring(0, lastSlash);
    }

    private static String normalisePath(String uriPath) {
        return (uriPath == null || uriPath.isEmpty()) ? "/" : uriPath;
    }

    /**
     * A single stored cookie. Immutable; equality of <em>identity</em>
     * (name + domain + path) governs replacement, matching browser behaviour.
     */
    public static final class Cookie {
        private final String name;
        private final String value;
        private final String domain;
        private final String path;
        private final Instant expiry;   // null => session cookie (never auto-expires)
        private final boolean secure;
        private final boolean hostOnly; // true when no Domain attribute was sent

        Cookie(String name, String value, String domain, String path,
               Instant expiry, boolean secure, boolean hostOnly) {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.expiry = expiry;
            this.secure = secure;
            this.hostOnly = hostOnly;
        }

        public String getName() { return name; }
        public String getValue() { return value; }
        public String getDomain() { return domain; }
        public String getPath() { return path; }
        public boolean isSecure() { return secure; }
        public boolean isHostOnly() { return hostOnly; }
        /** Absolute expiry instant, or {@code null} for a session cookie. */
        public Instant getExpiry() { return expiry; }

        boolean isExpiredAt(Instant now) {
            return expiry != null && !expiry.isAfter(now);
        }

        boolean sameIdentity(Cookie other) {
            return name.equals(other.name)
                    && domain.equals(other.domain)
                    && path.equals(other.path);
        }

        boolean domainMatches(String host) {
            if (host.equals(domain)) return true;
            if (hostOnly) return false; // host-only cookies require an exact match
            // Subdomain match: host ends with "." + domain (and domain isn't an IP-ish suffix).
            return host.endsWith("." + domain);
        }

        boolean pathMatches(String requestPath) {
            if (requestPath.equals(path)) return true;
            if (!requestPath.startsWith(path)) return false;
            // Either the cookie path ends in "/", or the next char in the request is "/".
            return path.endsWith("/") || requestPath.charAt(path.length()) == '/';
        }

        @Override
        public String toString() {
            return name + "=" + value + " (domain=" + domain + ", path=" + path
                    + (secure ? ", secure" : "") + (hostOnly ? ", host-only" : "") + ")";
        }
    }
}
