package com.nexuslink.protocol.redis;

import java.util.ArrayList;
import java.util.List;

/**
 * The deployment shape behind a connection URI.
 *
 * <p>Lettuce understands {@code redis://}, {@code rediss://} and {@code redis-sentinel://} natively,
 * but has no URI form for a cluster seed list. We add our own {@code redis-cluster://} scheme:
 *
 * <pre>redis-cluster://[:password@]host1:6379,host2:6379[/db]</pre>
 *
 * <p>{@link #seedUris(String)} rewrites such a URI into one plain {@code redis://} URI per seed node,
 * carrying the userinfo and the trailing database onto every node so each seed is independently
 * connectable.
 */
public enum RedisTopology {

    STANDALONE,
    SENTINEL,
    CLUSTER;

    /** A short human-readable name, for a status line. */
    public String label() {
        return switch (this) {
            case STANDALONE -> "standalone";
            case SENTINEL -> "sentinel";
            case CLUSTER -> "cluster";
        };
    }

    private static final String CLUSTER_SCHEME = "redis-cluster://";
    private static final String CLUSTER_TLS_SCHEME = "rediss-cluster://";

    /** The topology implied by {@code uri}; {@link #STANDALONE} for anything unrecognised. */
    public static RedisTopology of(String uri) {
        String s = uri == null ? "" : uri.trim().toLowerCase();
        if (s.startsWith(CLUSTER_SCHEME) || s.startsWith(CLUSTER_TLS_SCHEME)) return CLUSTER;
        if (s.startsWith("redis-sentinel://")) return SENTINEL;
        return STANDALONE;
    }

    /**
     * Expands {@code uri} into the seed URIs to hand to a client.
     *
     * <p>For everything but a {@code redis-cluster://} URI this is the URI itself, untouched. A
     * cluster URI yields one {@code redis://} (or {@code rediss://}) URI per comma-separated host,
     * each carrying the original userinfo and database.
     */
    public static List<String> seedUris(String uri) {
        String s = uri == null ? "" : uri.trim();
        String lower = s.toLowerCase();
        boolean tls = lower.startsWith(CLUSTER_TLS_SCHEME);
        if (!tls && !lower.startsWith(CLUSTER_SCHEME)) return List.of(s);

        String scheme = tls ? "rediss://" : "redis://";
        String body = s.substring((tls ? CLUSTER_TLS_SCHEME : CLUSTER_SCHEME).length());

        // Split off userinfo (last '@' — a password may itself contain '@').
        String userinfo = "";
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            userinfo = body.substring(0, at + 1);
            body = body.substring(at + 1);
        }

        // Split off the trailing /db (and anything after it).
        String tail = "";
        int slash = body.indexOf('/');
        if (slash >= 0) {
            tail = body.substring(slash);
            body = body.substring(0, slash);
        }

        List<String> seeds = new ArrayList<>();
        for (String host : body.split(",")) {
            String h = host.trim();
            if (!h.isEmpty()) seeds.add(scheme + userinfo + h + tail);
        }
        return seeds.isEmpty() ? List.of(scheme + userinfo + tail) : List.copyOf(seeds);
    }

    private static final String SENTINEL_SCHEME = "redis-sentinel://";

    /**
     * The monitored master name in a {@code redis-sentinel://…#master} URI — the fragment, defaulting
     * to Lettuce's own default of {@code mymaster} when absent.
     */
    public static String masterName(String uri) {
        String s = uri == null ? "" : uri.trim();
        int hash = s.indexOf('#');
        if (hash < 0 || hash == s.length() - 1) return "mymaster";
        return s.substring(hash + 1);
    }

    /**
     * The sentinel nodes in a {@code redis-sentinel://} URI, each as a plain {@code redis://} URI to
     * be queried with {@code SENTINEL get-master-addr-by-name}.
     *
     * <p>The sentinel port defaults to 26379 when a host carries none. Returns an empty list for a
     * URI that isn't a sentinel URI.
     */
    public static List<String> sentinelSeedUris(String uri) {
        String s = uri == null ? "" : uri.trim();
        if (!s.toLowerCase().startsWith(SENTINEL_SCHEME)) return List.of();

        String body = s.substring(SENTINEL_SCHEME.length());
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);

        String userinfo = "";
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            userinfo = body.substring(0, at + 1);
            body = body.substring(at + 1);
        }
        int slash = body.indexOf('/');
        if (slash >= 0) body = body.substring(0, slash);

        List<String> seeds = new ArrayList<>();
        for (String host : body.split(",")) {
            String h = host.trim();
            if (h.isEmpty()) continue;
            if (!hasPort(h)) h = h + ":26379";
            seeds.add("redis://" + userinfo + h);
        }
        return List.copyOf(seeds);
    }

    /** True when {@code hostPort} ends in {@code :digits} (bracketed IPv6 literals included). */
    private static boolean hasPort(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0 || colon == hostPort.length() - 1) return false;
        if (hostPort.indexOf(']') > colon) return false; // colon was inside an IPv6 literal
        for (int i = colon + 1; i < hostPort.length(); i++) {
            if (!Character.isDigit(hostPort.charAt(i))) return false;
        }
        return true;
    }
}
