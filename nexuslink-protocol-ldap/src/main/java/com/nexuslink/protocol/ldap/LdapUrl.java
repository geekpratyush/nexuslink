package com.nexuslink.protocol.ldap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A parsed LDAP URL (RFC 4516):
 * {@code ldap[s]://[host[:port]]/[dn][?attributes[?scope[?filter[?extensions]]]]}.
 *
 * <p>This is pure, offline logic — no directory connection is involved. Parsing splits the URL into
 * its five optional query sections, percent-decodes (RFC 3986 §2.1) the DN, filter, attribute and
 * extension text, and applies the RFC 4516 defaulting rules (scope {@code base}, filter
 * {@code (objectClass=*)}, port 389 for {@code ldap} / 636 for {@code ldaps}). {@link #toString()}
 * reverses the process, percent-encoding the characters that would otherwise collide with URL
 * delimiters, so that {@code LdapUrl.parse(s).toString()} yields an equivalent URL. The base DN is
 * held as a validated {@link Dn}; the filter is kept as its RFC 4515 string.
 */
public final class LdapUrl {

    /** Search scope carried in the third query section of an LDAP URL (RFC 4516 §2). */
    public enum Scope {
        /** {@code base} — the base object only (default when the scope section is absent or empty). */
        BASE("base"),
        /** {@code one} — the immediate children of the base object. */
        ONE("one"),
        /** {@code sub} — the base object and its whole subtree. */
        SUB("sub");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        /** The lower-case token as it appears in a URL ({@code base}/{@code one}/{@code sub}). */
        public String label() {
            return label;
        }

        /** Parse a scope token (case-insensitive); {@code onelevel}/{@code subtree} are also accepted. */
        static Scope of(String token) {
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "base", "baseobject" -> BASE;
                case "one", "onelevel" -> ONE;
                case "sub", "subtree" -> SUB;
                default -> throw new LdapUrlException("Unknown search scope: " + token);
            };
        }
    }

    /**
     * One URL extension (RFC 4516 §2): an optional {@code !}-critical marker, a type, and an optional
     * value. A critical extension the server does not understand must cause the operation to fail.
     */
    public record Extension(boolean critical, String type, String value) {
        public Extension {
            Objects.requireNonNull(type, "type");
            if (type.isBlank()) {
                throw new LdapUrlException("Empty extension type");
            }
        }

        /** Render as {@code [!]type[=value]} with type and value percent-encoded. */
        String render() {
            StringBuilder sb = new StringBuilder();
            if (critical) {
                sb.append('!');
            }
            sb.append(percentEncode(type, true));
            if (value != null) {
                sb.append('=').append(percentEncode(value, true));
            }
            return sb.toString();
        }
    }

    /** Thrown when an LDAP URL (or one of its components) is malformed. */
    public static final class LdapUrlException extends IllegalArgumentException {
        public LdapUrlException(String message) {
            super(message);
        }

        public LdapUrlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Default filter applied when the filter section is absent or empty (RFC 4516 §2). */
    public static final String DEFAULT_FILTER = "(objectClass=*)";

    private static final int DEFAULT_PORT_LDAP = 389;
    private static final int DEFAULT_PORT_LDAPS = 636;

    private final boolean secure;
    private final String host;
    private final int port;
    private final Dn baseDn;
    private final List<String> attributes;
    private final Scope scope;
    private final String filter;
    private final List<Extension> extensions;

    private LdapUrl(boolean secure, String host, int port, Dn baseDn, List<String> attributes,
                    Scope scope, String filter, List<Extension> extensions) {
        this.secure = secure;
        this.host = host;
        this.port = port;
        this.baseDn = baseDn;
        this.attributes = List.copyOf(attributes);
        this.scope = scope;
        this.filter = filter;
        this.extensions = List.copyOf(extensions);
    }

    /** Parse an RFC 4516 LDAP URL, applying the RFC's defaulting rules. */
    public static LdapUrl parse(String url) {
        Objects.requireNonNull(url, "url");
        boolean secure;
        String rest;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ldaps://")) {
            secure = true;
            rest = url.substring("ldaps://".length());
        } else if (lower.startsWith("ldap://")) {
            secure = false;
            rest = url.substring("ldap://".length());
        } else {
            throw new LdapUrlException("URL must start with ldap:// or ldaps://: " + url);
        }

        // Split authority from the path/query at the first '/'.
        String authority;
        String path;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            authority = rest;
            path = null;
        } else {
            authority = rest.substring(0, slash);
            path = rest.substring(slash + 1);
        }

        String host = null;
        int port = secure ? DEFAULT_PORT_LDAPS : DEFAULT_PORT_LDAP;
        if (!authority.isEmpty()) {
            HostPort hp = parseAuthority(authority);
            host = hp.host;
            if (hp.port >= 0) {
                port = hp.port;
            }
        }

        Dn baseDn = Dn.EMPTY;
        List<String> attributes = new ArrayList<>();
        Scope scope = Scope.BASE;
        String filter = DEFAULT_FILTER;
        List<Extension> extensions = new ArrayList<>();

        if (path != null && !path.isEmpty()) {
            // dn ? attrs ? scope ? filter ? extensions  (extensions keeps any further '?')
            String[] parts = path.split("\\?", 5);

            String dnText = percentDecode(parts[0]);
            try {
                baseDn = Dn.parse(dnText);
            } catch (IllegalArgumentException e) {
                throw new LdapUrlException("Invalid base DN in LDAP URL: " + dnText, e);
            }

            if (parts.length > 1) {
                attributes = parseAttributes(parts[1]);
            }
            if (parts.length > 2 && !parts[2].isEmpty()) {
                scope = Scope.of(percentDecode(parts[2]));
            }
            if (parts.length > 3 && !parts[3].isEmpty()) {
                filter = percentDecode(parts[3]);
            }
            if (parts.length > 4 && !parts[4].isEmpty()) {
                extensions = parseExtensions(parts[4]);
            }
        }

        return new LdapUrl(secure, host, port, baseDn, attributes, scope, filter, extensions);
    }

    /**
     * Build an LDAP URL directly. {@code host} may be {@code null} for a host-less URL; {@code port}
     * &lt; 0 selects the scheme default; a {@code null} {@code baseDn}/{@code filter} takes the RFC
     * default ({@link Dn#EMPTY} / {@link #DEFAULT_FILTER}).
     */
    public static LdapUrl of(boolean secure, String host, int port, Dn baseDn,
                             List<String> attributes, Scope scope, String filter,
                             List<Extension> extensions) {
        int effectivePort = port >= 0 ? port : (secure ? DEFAULT_PORT_LDAPS : DEFAULT_PORT_LDAP);
        if (port >= 0 && (port > 0xFFFF)) {
            throw new LdapUrlException("Port out of range: " + port);
        }
        return new LdapUrl(secure,
                host,
                effectivePort,
                baseDn == null ? Dn.EMPTY : baseDn,
                attributes == null ? List.of() : attributes,
                scope == null ? Scope.BASE : scope,
                filter == null || filter.isEmpty() ? DEFAULT_FILTER : filter,
                extensions == null ? List.of() : extensions);
    }

    // --- accessors -------------------------------------------------------------------------------

    /** True for the {@code ldaps} scheme (LDAP over TLS). */
    public boolean isSecure() {
        return secure;
    }

    /** The scheme token, {@code "ldap"} or {@code "ldaps"}. */
    public String scheme() {
        return secure ? "ldaps" : "ldap";
    }

    /** The host, or {@code null} for a host-less URL such as {@code ldap:///}. */
    public String host() {
        return host;
    }

    /** The effective port (explicit, or the scheme default 389/636). */
    public int port() {
        return port;
    }

    /** True if {@link #port()} is the scheme default (389 for ldap, 636 for ldaps). */
    public boolean isDefaultPort() {
        return port == (secure ? DEFAULT_PORT_LDAPS : DEFAULT_PORT_LDAP);
    }

    /** The base DN ({@link Dn#EMPTY} when absent). */
    public Dn baseDn() {
        return baseDn;
    }

    /** The requested attribute list; an empty list means "all user attributes". */
    public List<String> attributes() {
        return attributes;
    }

    /** The search scope (default {@link Scope#BASE}). */
    public Scope scope() {
        return scope;
    }

    /** The RFC 4515 filter string (default {@link #DEFAULT_FILTER}). */
    public String filter() {
        return filter;
    }

    /** The URL extensions, in order. */
    public List<Extension> extensions() {
        return extensions;
    }

    // --- rendering -------------------------------------------------------------------------------

    /**
     * Render back to an RFC 4516 URL. Trailing default sections are omitted; percent-encoding is
     * applied to characters that would otherwise be read as URL delimiters, so that re-parsing the
     * result yields an equivalent {@code LdapUrl}.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme()).append("://");
        if (host != null) {
            sb.append(host);
        }
        if (!isDefaultPort()) {
            sb.append(':').append(port);
        }

        String attrsSection = String.join(",", attributes.stream().map(a -> percentEncode(a, false)).toList());
        String scopeSection = scope == Scope.BASE ? "" : scope.label();
        String filterSection = filter.equals(DEFAULT_FILTER) ? "" : percentEncode(filter, false);
        String extSection = renderExtensions();

        String[] sections = {attrsSection, scopeSection, filterSection, extSection};
        int last = -1;
        for (int i = 0; i < sections.length; i++) {
            if (!sections[i].isEmpty()) {
                last = i;
            }
        }

        boolean hasQuery = last >= 0;
        // A host-less URL keeps the canonical trailing slash (e.g. "ldap:///").
        boolean hasPath = !baseDn.isEmpty() || hasQuery || host == null;
        if (hasPath) {
            sb.append('/');
            sb.append(percentEncode(baseDn.toString(), false));
        }
        for (int i = 0; i <= last; i++) {
            sb.append('?').append(sections[i]);
        }
        return sb.toString();
    }

    private String renderExtensions() {
        if (extensions.isEmpty()) {
            return "";
        }
        List<String> rendered = new ArrayList<>(extensions.size());
        for (Extension e : extensions) {
            rendered.add(e.render());
        }
        return String.join(",", rendered);
    }

    // --- equality (component-wise, LDAP case rules) ----------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LdapUrl other)) {
            return false;
        }
        return secure == other.secure
                && port == other.port
                && Objects.equals(lower(host), lower(other.host))
                && baseDn.equals(other.baseDn)
                && lowerAll(attributes).equals(lowerAll(other.attributes))
                && scope == other.scope
                && filter.equals(other.filter)
                && extensions.equals(other.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(secure, port, lower(host), baseDn, lowerAll(attributes), scope, filter, extensions);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static List<String> lowerAll(List<String> in) {
        return in.stream().map(a -> a.toLowerCase(Locale.ROOT)).toList();
    }

    // --- authority / section parsing -------------------------------------------------------------

    private record HostPort(String host, int port) {
    }

    private static HostPort parseAuthority(String authority) {
        String hostPart;
        String portPart;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                throw new LdapUrlException("Unterminated IPv6 literal in host: " + authority);
            }
            hostPart = authority.substring(0, close + 1);
            String tail = authority.substring(close + 1);
            if (tail.isEmpty()) {
                portPart = null;
            } else if (tail.charAt(0) == ':') {
                portPart = tail.substring(1);
            } else {
                throw new LdapUrlException("Unexpected text after IPv6 literal: " + authority);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon < 0) {
                hostPart = authority;
                portPart = null;
            } else {
                hostPart = authority.substring(0, colon);
                portPart = authority.substring(colon + 1);
            }
        }
        int port = -1;
        if (portPart != null) {
            if (portPart.isEmpty()) {
                throw new LdapUrlException("Empty port in host: " + authority);
            }
            try {
                port = Integer.parseInt(portPart);
            } catch (NumberFormatException e) {
                throw new LdapUrlException("Invalid port in host: " + authority, e);
            }
            if (port < 0 || port > 0xFFFF) {
                throw new LdapUrlException("Port out of range: " + port);
            }
        }
        return new HostPort(percentDecode(hostPart), port);
    }

    private static List<String> parseAttributes(String section) {
        List<String> out = new ArrayList<>();
        if (section.isEmpty()) {
            return out;
        }
        for (String raw : section.split(",", -1)) {
            String attr = percentDecode(raw).trim();
            if (!attr.isEmpty()) {
                out.add(attr);
            }
        }
        return out;
    }

    private static List<Extension> parseExtensions(String section) {
        List<Extension> out = new ArrayList<>();
        for (String raw : section.split(",", -1)) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            boolean critical = false;
            if (token.charAt(0) == '!') {
                critical = true;
                token = token.substring(1);
            }
            int eq = token.indexOf('=');
            String type;
            String value;
            if (eq < 0) {
                type = percentDecode(token);
                value = null;
            } else {
                type = percentDecode(token.substring(0, eq));
                value = percentDecode(token.substring(eq + 1));
            }
            out.add(new Extension(critical, type, value));
        }
        return out;
    }

    // --- percent-encoding (RFC 3986 §2.1, UTF-8) -------------------------------------------------

    /**
     * Percent-decode a component. {@code %HH} sequences are collected as raw octets and interpreted as
     * UTF-8; all other characters pass through unchanged.
     */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length() || !isHex(s.charAt(i + 1)) || !isHex(s.charAt(i + 2))) {
                    throw new LdapUrlException("Invalid percent-encoding in: " + s);
                }
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    out.write(b);
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Percent-encode a component. Unreserved characters (RFC 3986 §2.3) pass through; the URL
     * delimiters that would otherwise break the LDAP URL grammar ({@code % ? # \ < > "}, space and
     * control/non-ASCII octets) are escaped. When {@code extensionContext} is true, {@code ,} and
     * {@code !} are escaped as well, since they are structural inside the extensions section.
     */
    static String percentEncode(String s, boolean extensionContext) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int u = b & 0xFF;
            char c = (char) u;
            boolean safe = u >= 0x20 && u <= 0x7E && !isDelimiter(c)
                    && !(extensionContext && (c == ',' || c == '!'));
            if (safe) {
                sb.append(c);
            } else {
                sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit((u >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(u & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    private static boolean isDelimiter(char c) {
        return switch (c) {
            case '%', '?', '#', '\\', '<', '>', '"', ' ' -> true;
            default -> false;
        };
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
