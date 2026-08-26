package com.nexuslink.protocol.mongo;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the connected MongoDB deployment actually is — product, version and topology — and which of
 * the commands this client uses it will accept.
 *
 * <p>"MongoDB" is not one command set. {@code collStats} has been deprecated since 6.2 in favour of
 * the {@code $collStats} aggregation stage; user administration is unavailable on shared Atlas
 * tiers and on Amazon DocumentDB; transactions need a replica set or a sharded cluster; and the
 * wire-compatible imitations (DocumentDB, Cosmos DB's Mongo API, FerretDB) report a MongoDB version
 * they do not fully implement. Deciding all of that from the server's own {@code buildInfo} and
 * {@code hello} replies — rather than assuming — is what keeps the client honest.
 *
 * <p>Pure: {@link #of} takes the two reply documents as plain maps, so every rule is testable
 * without a server.
 */
public record MongoServerInfo(String product, String version, Topology topology) {

    /** How the deployment is put together, which decides what transactions and reads can do. */
    public enum Topology {
        /** A single {@code mongod} — no transactions, no change streams. */
        STANDALONE,
        /** A replica set — transactions and change streams available. */
        REPLICA_SET,
        /** A sharded cluster behind {@code mongos}. */
        SHARDED
    }

    /** Used before a connection exists, or when the server refuses the handshake commands. */
    public static MongoServerInfo unknown() {
        return new MongoServerInfo("MongoDB", "", Topology.STANDALONE);
    }

    /**
     * Reads the deployment from {@code buildInfo} and {@code hello} (or the legacy {@code isMaster}).
     * Either may be null or partial — a locked-down deployment answers neither, and the result then
     * falls back to {@link #unknown()} rather than throwing.
     */
    public static MongoServerInfo of(Map<String, Object> buildInfo, Map<String, Object> hello) {
        String version = buildInfo == null ? "" : String.valueOf(buildInfo.getOrDefault("version", ""));
        if ("null".equals(version)) version = "";

        String product = "MongoDB";
        if (buildInfo != null) {
            // The imitations name themselves in buildInfo, each in their own way.
            String all = String.valueOf(buildInfo).toLowerCase(Locale.ROOT);
            if (all.contains("documentdb")) product = "Amazon DocumentDB";
            else if (all.contains("cosmos")) product = "Azure Cosmos DB";
            else if (all.contains("ferretdb")) product = "FerretDB";
            else if (buildInfo.containsKey("psmdbVersion")) product = "Percona Server for MongoDB";
        }

        Topology topology = Topology.STANDALONE;
        if (hello != null) {
            if ("isdbgrid".equals(hello.get("msg"))) topology = Topology.SHARDED;
            else if (hello.get("setName") != null) topology = Topology.REPLICA_SET;
        }
        return new MongoServerInfo(product, version, topology);
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

    /** {@code true} when the server is at least {@code major.minor}; an unknown version answers false. */
    public boolean atLeast(int major, int minor) {
        if (version == null || version.isBlank()) return false;
        return major() > major || (major() == major && minor() >= minor);
    }

    /** {@code true} for anything that is not the real MongoDB server. */
    public boolean isImitation() { return !"MongoDB".equals(product) && !product.startsWith("Percona"); }

    /**
     * {@code true} when collection statistics should be read with the {@code $collStats} aggregation
     * stage rather than the {@code collStats} command — the command is deprecated from 6.2 and is
     * blocked outright on shared Atlas tiers, while the stage works on every 4.4+ deployment.
     */
    public boolean prefersCollStatsAggregation() {
        return atLeast(6, 2);
    }

    /**
     * {@code true} when the deployment can run multi-document transactions: a replica set, or a
     * sharded cluster on 4.2+. A standalone {@code mongod} cannot, whatever its version.
     */
    public boolean supportsTransactions() {
        return switch (topology) {
            case REPLICA_SET -> atLeast(4, 0);
            case SHARDED -> atLeast(4, 2);
            case STANDALONE -> false;
        };
    }

    /** {@code true} when change streams are available — they need an oplog, so not on a standalone. */
    public boolean supportsChangeStreams() { return topology != Topology.STANDALONE; }

    /** {@code true} when {@code usersInfo} / {@code createUser} are expected to work. */
    public boolean supportsUserAdministration() {
        return !"Amazon DocumentDB".equals(product) && !"Azure Cosmos DB".equals(product);
    }

    /** A short label for the status bar, e.g. {@code MongoDB 7.0.5 · replica set}. */
    public String label() {
        String v = version == null || version.isBlank() ? "" : " " + version;
        String shape = switch (topology) {
            case REPLICA_SET -> "replica set";
            case SHARDED -> "sharded cluster";
            case STANDALONE -> "standalone";
        };
        return product + v + " · " + shape;
    }

    /** Names, for the UI, whatever this deployment cannot do — empty when it is a full MongoDB. */
    public List<String> limitations() {
        List<String> out = new java.util.ArrayList<>();
        if (!supportsTransactions()) {
            out.add("no multi-document transactions (" + (topology == Topology.STANDALONE
                    ? "standalone deployment" : "server too old") + ")");
        }
        if (!supportsChangeStreams()) out.add("no change streams (standalone deployment)");
        if (!supportsUserAdministration()) out.add("no user administration on " + product);
        return out;
    }
}
