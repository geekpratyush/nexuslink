package com.nexuslink.core.connection;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Per-protocol pre-save validation of a {@link ConnectionProfile}. Pure and side-effect free, so it
 * is fully unit-testable and can run on the UI thread before {@link ConnectionStore#save} — the
 * editor shows {@link Result#errors()} and blocks the save while {@link Result#valid()} is false.
 *
 * <p>Checks fall into three groups: the name, the protocol-specific shape of {@link
 * ConnectionProfile#target}, and the fields an {@link AuthMethod} requires (a username for BASIC,
 * a secret reference for token/key methods, a keystore for mutual TLS, and so on). Secret <em>values</em>
 * are never inspected — only that the expected reference/setting keys are present and non-blank.
 */
public final class ProfileValidator {

    /** Outcome of validation: an ordered list of human-readable problems (empty ⇒ valid). */
    public record Result(List<String> errors) {
        public boolean valid() { return errors.isEmpty(); }
        public String summary() { return String.join("; ", errors); }
    }

    private static final Pattern HOST_PORT =
            Pattern.compile("^[A-Za-z0-9._-]+:\\d{1,5}$");
    private static final Pattern HOST_OPT_PORT =
            Pattern.compile("^[A-Za-z0-9._-]+(:\\d{1,5})?$");

    private ProfileValidator() {}

    public static Result validate(ConnectionProfile p) {
        List<String> errors = new ArrayList<>();
        if (p == null) {
            errors.add("Profile is missing");
            return new Result(errors);
        }
        if (isBlank(p.name)) errors.add("Name is required");
        if (p.protocol == null) {
            errors.add("Protocol is required");
            return new Result(errors);
        }
        validateTarget(p, errors);
        validateAuth(p, errors);
        return new Result(errors);
    }

    private static void validateTarget(ConnectionProfile p, List<String> errors) {
        String t = p.target == null ? "" : p.target.trim();
        // The LLM tester has no endpoint; everything else needs a target.
        if (p.protocol == ConnectionProfile.Protocol.LLM) return;
        if (isBlank(t)) {
            errors.add("Target endpoint is required");
            return;
        }
        switch (p.protocol) {
            case REST, GRAPHQL, SSE -> requireScheme(t, errors, "http", "https");
            case WEBSOCKET -> requireScheme(t, errors, "ws", "wss");
            case GRPC -> { if (!HOST_PORT.matcher(t).matches() && !hasScheme(t))
                    errors.add("gRPC target should be host:port (e.g. api.example.com:443)"); }
            case SQL -> { if (!t.toLowerCase().startsWith("jdbc:"))
                    errors.add("JDBC URL must start with 'jdbc:' (e.g. jdbc:postgresql://host:5432/db)"); }
            case MONGO -> { if (!t.startsWith("mongodb://") && !t.startsWith("mongodb+srv://"))
                    errors.add("MongoDB target must start with mongodb:// or mongodb+srv://"); }
            case REDIS -> { if (!t.startsWith("redis://") && !t.startsWith("rediss://")
                    && !HOST_OPT_PORT.matcher(t).matches())
                    errors.add("Redis target should be redis://host:6379 or host:port"); }
            case KAFKA -> validateKafkaBootstrap(t, errors);
            case MQTT -> requireScheme(t, errors, "tcp", "ssl", "ws", "wss");
            case JMS -> requireScheme(t, errors, "tcp", "ssl", "amqp", "amqps");
            case MQ -> { if (!HOST_PORT.matcher(t).matches())
                    errors.add("MQ target should be host:port (the queue-manager listener)"); }
            case SFTP, FTP -> { if (!HOST_OPT_PORT.matcher(t).matches())
                    errors.add("Target should be a host or host:port"); }
            case MCP -> { /* HTTP(S) URL or a stdio command line — accept either */ }
            case S3, AZURE_BLOB, GCS -> { /* endpoints/buckets vary by vendor — leave to live connect */ }
            default -> { /* no extra shape checks */ }
        }
    }

    private static void validateKafkaBootstrap(String t, List<String> errors) {
        for (String hp : t.split(",")) {
            if (!HOST_PORT.matcher(hp.trim()).matches()) {
                errors.add("Kafka bootstrap should be comma-separated host:port pairs");
                return;
            }
        }
    }

    private static void validateAuth(ConnectionProfile p, List<String> errors) {
        AuthMethod auth = p.auth == null ? AuthMethod.NONE : p.auth;
        switch (auth) {
            case BASIC -> { if (isBlank(p.username)) errors.add("Basic auth requires a username"); }
            case BEARER_TOKEN -> requireSecret(p, errors, "Bearer auth requires a token (or vault reference)",
                    "token", "tokenRef", "bearerToken");
            case API_KEY -> requireSecret(p, errors, "API-key auth requires a key (or vault reference)",
                    "apiKey", "apiKeyValue", "apiKeyValueRef", "apiKeyRef");
            case CONNECTION_STRING -> { /* the connection string lives in target — already checked */ }
            case MUTUAL_TLS -> requireProp(p, errors, "keystorePath", "Mutual TLS requires a client keystore path");
            case SASL_PLAIN, SASL_SCRAM -> { if (isBlank(p.username))
                    errors.add(auth.label() + " requires a username"); }
            case KERBEROS -> requireProp(p, errors, "principal", "Kerberos requires a principal");
            case OAUTH2 -> {
                requireProp(p, errors, "tokenUrl", "OAuth 2.0 requires a token URL");
                requireProp(p, errors, "clientId", "OAuth 2.0 requires a client id");
            }
            case SSH_KEY -> requireProp(p, errors, "privateKeyPath", "SSH-key auth requires a private-key path");
            case AWS_SIGV4 -> requireSecret(p, errors, "AWS SigV4 requires an access key (or vault reference)",
                    "accessKey", "accessKeyId", "accessKeyRef");
            case TLS, NONE -> { /* nothing extra required */ }
        }
    }

    private static void requireScheme(String target, List<String> errors, String... schemes) {
        String scheme = schemeOf(target);
        if (scheme == null) {
            errors.add("Target must be a URL (e.g. " + schemes[0] + "://host)");
            return;
        }
        for (String s : schemes) if (s.equalsIgnoreCase(scheme)) return;
        errors.add("Target scheme must be one of " + String.join("/", schemes) + " (got '" + scheme + "')");
    }

    private static void requireProp(ConnectionProfile p, List<String> errors, String key, String message) {
        if (isBlank(p.authProps.get(key))) errors.add(message);
    }

    /** Passes when any candidate key (raw secret or its vaulted {@code *Ref} form) is present. */
    private static void requireSecret(ConnectionProfile p, List<String> errors, String message, String... keys) {
        for (String key : keys) {
            if (!isBlank(p.authProps.get(key)) || !isBlank(p.authProps.get(key + "Ref"))) return;
        }
        errors.add(message);
    }

    private static boolean hasScheme(String target) {
        return schemeOf(target) != null;
    }

    private static String schemeOf(String target) {
        try {
            URI u = URI.create(target);
            return u.getScheme();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
