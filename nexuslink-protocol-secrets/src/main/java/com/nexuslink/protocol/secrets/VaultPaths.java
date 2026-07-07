package com.nexuslink.protocol.secrets;

/**
 * Pure, dependency-free helpers for building HashiCorp Vault KV v2 API paths and normalising a
 * server address. Kept separate from {@link VaultService} so the URL logic (the fiddly part of a
 * KV v2 client — data vs. metadata endpoints) is unit-testable without a running Vault.
 *
 * <p>KV v2 routes secret reads/writes through {@code {mount}/data/{path}} and listing/metadata
 * through {@code {mount}/metadata/{path}} — unlike KV v1 where the mount and path are simply joined.
 */
public final class VaultPaths {

    private VaultPaths() {}

    /** Trims a server address and strips a trailing slash so {@code addr + "/v1/..."} is well-formed. */
    public static String normalizeAddress(String address) {
        if (address == null) throw new IllegalArgumentException("address is required");
        String a = address.trim();
        if (a.isEmpty()) throw new IllegalArgumentException("address is required");
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a;
    }

    /** Strips leading/trailing slashes from a mount or secret path segment. */
    static String trimSlashes(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Full API path for reading/writing a KV v2 secret's data, e.g. {@code v1/secret/data/app/db}. */
    public static String kv2DataPath(String mount, String path) {
        String m = trimSlashes(mount);
        if (m.isEmpty()) m = "secret";
        String p = trimSlashes(path);
        return "v1/" + m + "/data/" + p;
    }

    /** Full API path for a KV v2 secret's metadata (used for LIST + delete-all-versions). */
    public static String kv2MetadataPath(String mount, String path) {
        String m = trimSlashes(mount);
        if (m.isEmpty()) m = "secret";
        String p = trimSlashes(path);
        String base = "v1/" + m + "/metadata";
        return p.isEmpty() ? base : base + "/" + p;
    }

    /** Full API path for an AppRole login against an auth mount, e.g. {@code v1/auth/approle/login}. */
    public static String approleLoginPath(String authMount) {
        String m = trimSlashes(authMount);
        if (m.isEmpty()) m = "approle";
        return "v1/auth/" + m + "/login";
    }
}
