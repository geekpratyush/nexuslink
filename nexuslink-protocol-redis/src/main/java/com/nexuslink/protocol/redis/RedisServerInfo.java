package com.nexuslink.protocol.redis;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the server says it is, parsed from the {@code INFO server} reply: which product (Redis,
 * Valkey, KeyDB, Dragonfly…), which version, and which mode (standalone, cluster or sentinel).
 *
 * <p>The Redis command set is not one fixed language. {@code GETDEL} and {@code HRANDFIELD} arrive
 * in 6.2, {@code FUNCTION} and {@code EXPIRETIME} in 7.0, hash-field TTLs in 7.4; forks answer to
 * different names and version numbers; managed services block administrative commands entirely. This
 * class turns the server's own answer into a version we can compare against, so the client can say
 * "your server is 6.0, that command needs 7.0" instead of failing with a bare error.
 *
 * <p>Pure: {@link #parse} takes the INFO text, so every rule here is testable without a server.
 */
public record RedisServerInfo(String product, String version, String mode) {

    /** Used before a connection exists, or when the server refuses {@code INFO}. */
    public static RedisServerInfo unknown() {
        return new RedisServerInfo("Redis", "", "standalone");
    }

    /**
     * Parses an {@code INFO} reply. Unrecognised or missing fields fall back to the defaults in
     * {@link #unknown()} rather than throwing — INFO is a convenience, never a precondition.
     */
    public static RedisServerInfo parse(String info) {
        if (info == null || info.isBlank()) return unknown();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : info.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon > 0) fields.put(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }

        // Forks advertise themselves in their own field first, then in the shared ones.
        String product = "Redis";
        String version = fields.getOrDefault("redis_version", "");
        if (fields.containsKey("valkey_version")) {
            product = "Valkey";
            version = fields.get("valkey_version");
        } else if (fields.containsKey("dragonfly_version")) {
            product = "Dragonfly";
            version = fields.get("dragonfly_version");
        } else if ("keydb".equalsIgnoreCase(fields.getOrDefault("server_name", ""))) {
            product = "KeyDB";
            version = fields.getOrDefault("keydb_version", version);
        } else if (fields.containsKey("server_name")) {
            String name = fields.get("server_name");
            if (!name.isBlank() && !"redis".equalsIgnoreCase(name)) {
                product = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        }

        String mode = fields.getOrDefault("redis_mode", "standalone");
        return new RedisServerInfo(product, version, mode);
    }

    /** The major version, or 0 when the server did not report one. */
    public int major() { return versionPart(0); }

    /** The minor version, or 0 when the server did not report one. */
    public int minor() { return versionPart(1); }

    private int versionPart(int index) {
        if (version == null || version.isBlank()) return 0;
        String[] parts = version.split("\\.");
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index].replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code true} when the server is at least {@code major.minor}. An unknown version answers
     * {@code true}: the client should let the server refuse rather than block a command on a guess.
     */
    public boolean atLeast(int major, int minor) {
        if (version == null || version.isBlank()) return true;
        return major() > major || (major() == major && minor() >= minor);
    }

    /** {@code true} when this is a cluster-mode server. */
    public boolean isCluster() { return "cluster".equalsIgnoreCase(mode); }

    /** {@code true} when this connection is talking to a sentinel rather than a data node. */
    public boolean isSentinel() { return "sentinel".equalsIgnoreCase(mode); }

    /**
     * The version a command needs, when it is one of the well-known late arrivals — used to explain
     * an error rather than to pre-empt it. Empty when the command has no such requirement here.
     */
    public String requiredVersion(String command) {
        if (command == null) return "";
        return switch (command.toUpperCase(Locale.ROOT)) {
            case "GETDEL", "GETEX", "HRANDFIELD", "ZRANDMEMBER", "SMISMEMBER", "COPY" -> "6.2";
            case "FUNCTION", "EXPIRETIME", "PEXPIRETIME", "OBJECT", "SINTERCARD", "LMPOP", "ZMPOP" -> "7.0";
            case "HEXPIRE", "HPEXPIRE", "HTTL", "HPERSIST" -> "7.4";
            default -> "";
        };
    }

    /**
     * A hint for a failed command: names the version it needs when this server is older than that,
     * or an empty string when the version is not the problem.
     */
    public String versionHint(String command) {
        String required = requiredVersion(command);
        if (required.isEmpty() || version == null || version.isBlank()) return "";
        String[] parts = required.split("\\.");
        int needMajor = Integer.parseInt(parts[0]);
        int needMinor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        if (atLeast(needMajor, needMinor)) return "";
        return command.toUpperCase(Locale.ROOT) + " needs " + product + " " + required
                + "; this server reports " + version;
    }

    /** A short label for the status bar, e.g. {@code Valkey 7.2 · cluster}. */
    public String label() {
        String v = version == null || version.isBlank() ? "" : " " + version;
        return product + v + " · " + mode;
    }
}
