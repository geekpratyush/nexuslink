package com.nexuslink.core.connection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A saved (or sample) connection the user can reopen later. Protocol-agnostic: {@link #target}
 * holds the primary endpoint (a URL, JDBC URL, Mongo connection string, bootstrap servers, or
 * host:port), while {@link #auth} + {@link #authProps} describe how to authenticate so enterprise
 * setups (TLS/mTLS, SASL/SCRAM, Kerberos, OAuth2, SSH keys, …) are representable — not just
 * password-less access.
 *
 * <p>Plain public fields keep JSON (de)serialization trivial. Persisted by {@link ConnectionStore}.
 */
public final class ConnectionProfile {

    /** Protocols a profile can target. Some are connectable today; others are roadmap. */
    public enum Protocol {
        REST, WEBSOCKET, GRAPHQL, GRPC, SSE,
        SQL, MONGO, REDIS,
        MCP, LLM,
        KAFKA, MQTT, MQ,
        SFTP, FTP, S3, AZURE_BLOB, GCS
    }

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public Protocol protocol = Protocol.REST;
    public String target = "";
    public String username = "";
    public AuthMethod auth = AuthMethod.NONE;

    /** Secret references / method-specific settings (e.g. tokenRef, saslMechanism, keystorePath). */
    public Map<String, String> authProps = new LinkedHashMap<>();

    /** Extra protocol settings (e.g. jdbc driverId, mongo default database). */
    public Map<String, String> properties = new LinkedHashMap<>();

    /** True for bundled public sample endpoints (deletable/hideable, never auto-saved). */
    public boolean sample = false;

    public String notes = "";

    public ConnectionProfile() {}

    public ConnectionProfile(String name, Protocol protocol, String target) {
        this.name = name;
        this.protocol = protocol;
        this.target = target;
    }

    public ConnectionProfile withId(String id) { this.id = id; return this; }
    public ConnectionProfile withAuth(AuthMethod method) { this.auth = method; return this; }
    public ConnectionProfile withUser(String user) { this.username = user; return this; }
    public ConnectionProfile withNotes(String n) { this.notes = n; return this; }
    public ConnectionProfile prop(String k, String v) { this.properties.put(k, v); return this; }
    public ConnectionProfile authProp(String k, String v) { this.authProps.put(k, v); return this; }
    public ConnectionProfile asSample() { this.sample = true; return this; }

    public String iconHint() {
        return switch (protocol) {
            case REST -> "rest";
            case WEBSOCKET -> "ws";
            case GRAPHQL -> "rest";
            case GRPC -> "mcp";
            case SSE -> "topic";
            case SQL -> "sql";
            case MONGO -> "mongo";
            case REDIS -> "database";
            case MCP -> "mcp";
            case LLM -> "ai";
            case KAFKA -> "topic";
            case MQTT -> "topic";
            case MQ -> "queue-manager";
            case SFTP -> "server";
            case FTP -> "server";
            case S3 -> "collection";
            case AZURE_BLOB -> "collection";
            case GCS -> "collection";
        };
    }

    @Override public String toString() { return name; }
}
