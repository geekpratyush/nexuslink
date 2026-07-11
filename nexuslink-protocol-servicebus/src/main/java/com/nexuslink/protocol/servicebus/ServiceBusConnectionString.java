package com.nexuslink.protocol.servicebus;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, dependency-free parser for an Azure Service Bus connection string. Like the Storage strings,
 * these are a sequence of {@code Key=Value;} pairs with case-insensitive keys, e.g.
 * <pre>Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=abc==</pre>
 *
 * <p>The value inside {@code SharedAccessKey} may itself contain {@code =} padding, so pairs are split
 * on the <em>first</em> {@code =} only. This does no network I/O and pulls in no SDK; it exposes the
 * recognised fields plus the derived namespace host. It also recognises the
 * {@code UseDevelopmentEmulator=true} flag used by the local Service Bus emulator, whose endpoint is a
 * plain {@code sb://localhost} rather than a {@code *.servicebus.windows.net} host.
 */
public final class ServiceBusConnectionString {

    private final String endpoint;
    private final String sharedAccessKeyName;
    private final String sharedAccessKey;
    private final String entityPath;
    private final boolean development;

    private ServiceBusConnectionString(String endpoint, String sharedAccessKeyName,
                                       String sharedAccessKey, String entityPath, boolean development) {
        this.endpoint = endpoint;
        this.sharedAccessKeyName = sharedAccessKeyName;
        this.sharedAccessKey = sharedAccessKey;
        this.entityPath = entityPath;
        this.development = development;
    }

    /** Raised when a connection string is empty, malformed, or missing required fields. */
    public static final class MalformedConnectionStringException extends IllegalArgumentException {
        public MalformedConnectionStringException(String message) {
            super(message);
        }
    }

    /**
     * Parses an Azure Service Bus connection string.
     *
     * @throws MalformedConnectionStringException if the input is empty, has a malformed pair,
     *         a bad {@code Endpoint} scheme, or is missing the {@code Endpoint} field
     */
    public static ServiceBusConnectionString parse(String connectionString) {
        if (connectionString == null || connectionString.trim().isEmpty()) {
            throw new MalformedConnectionStringException("Connection string is empty");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String segment : connectionString.split(";")) {
            String pair = segment.trim();
            if (pair.isEmpty()) {
                continue; // tolerate trailing/duplicate separators
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new MalformedConnectionStringException("Malformed pair (missing '='): " + pair);
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim(); // split on FIRST '=' — SAS keys carry '=' padding
            if (key.isEmpty()) {
                throw new MalformedConnectionStringException("Malformed pair (empty key): " + pair);
            }
            values.put(key.toLowerCase(Locale.ROOT), value);
        }
        if (values.isEmpty()) {
            throw new MalformedConnectionStringException("Connection string has no key/value pairs");
        }

        String endpoint = emptyToNull(values.get("endpoint"));
        if (endpoint == null) {
            throw new MalformedConnectionStringException("Connection string must define an Endpoint");
        }
        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("sb://") && !lower.startsWith("amqps://") && !lower.startsWith("amqp://")) {
            throw new MalformedConnectionStringException(
                    "Endpoint must use the sb:// (or amqp[s]://) scheme, was: " + endpoint);
        }

        boolean development = false;
        String devRaw = values.get("usedevelopmentemulator");
        if (devRaw != null) {
            if (!devRaw.equalsIgnoreCase("true") && !devRaw.equalsIgnoreCase("false")) {
                throw new MalformedConnectionStringException(
                        "UseDevelopmentEmulator must be 'true' or 'false', was: " + devRaw);
            }
            development = devRaw.equalsIgnoreCase("true");
        }

        return new ServiceBusConnectionString(endpoint,
                emptyToNull(values.get("sharedaccesskeyname")),
                emptyToNull(values.get("sharedaccesskey")),
                emptyToNull(values.get("entitypath")),
                development);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** The raw {@code Endpoint} value, e.g. {@code sb://ns.servicebus.windows.net/}. */
    public String endpoint() {
        return endpoint;
    }

    /** The shared-access-policy name, or {@code null} when absent (e.g. a token-auth string). */
    public String sharedAccessKeyName() {
        return sharedAccessKeyName;
    }

    /** The shared-access key, or {@code null} when absent. */
    public String sharedAccessKey() {
        return sharedAccessKey;
    }

    /** The optional {@code EntityPath} (a specific queue or topic), or {@code null}. */
    public String entityPath() {
        return entityPath;
    }

    /** {@code true} when this string targets the local Service Bus development emulator. */
    public boolean isDevelopment() {
        return development;
    }

    /**
     * The namespace host derived from the endpoint: the authority with any scheme, trailing slash,
     * and port stripped — e.g. {@code ns.servicebus.windows.net} or {@code localhost}.
     */
    public String namespace() {
        String host = endpoint;
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host;
    }

    /** A redacted view of this connection string with the access key masked. */
    public String redacted() {
        StringBuilder sb = new StringBuilder("ServiceBusConnectionString{");
        sb.append("endpoint=").append(endpoint);
        sb.append(", keyName=").append(sharedAccessKeyName);
        sb.append(", key=").append(sharedAccessKey == null ? "null" : "***");
        if (entityPath != null) {
            sb.append(", entityPath=").append(entityPath);
        }
        if (development) {
            sb.append(", development=true");
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() {
        return redacted();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceBusConnectionString other)) {
            return false;
        }
        return development == other.development
                && Objects.equals(endpoint, other.endpoint)
                && Objects.equals(sharedAccessKeyName, other.sharedAccessKeyName)
                && Objects.equals(sharedAccessKey, other.sharedAccessKey)
                && Objects.equals(entityPath, other.entityPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoint, sharedAccessKeyName, sharedAccessKey, entityPath, development);
    }
}
