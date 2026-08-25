package com.nexuslink.ui.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * The non-JDBC database clients that ship inside NexusLink at a pinned version — Redis and MongoDB
 * speak their own wire protocols, so they never appear in the JDBC driver list.
 *
 * <p>This exists purely to answer a question the driver manager otherwise leaves open: having seen
 * that Oracle needs a driver installed, a user reasonably wonders whether Redis does too. It does
 * not. Versions are read from each driver jar's manifest at runtime rather than written down here,
 * so this can't drift from what actually shipped.
 */
final class BundledClients {

    /** A bundled client: what it is, and which driver build is in this app. */
    record Client(String name, String driver, String version) {

        String describe() {
            return name + " — " + driver + (version.isBlank() ? "" : " " + version);
        }
    }

    private BundledClients() {}

    /** The bundled non-JDBC clients, in the order they're listed to the user. */
    static List<Client> all() {
        List<Client> clients = new ArrayList<>();
        clients.add(new Client("Redis", "Lettuce", versionOf(io.lettuce.core.RedisClient.class)));
        clients.add(new Client("MongoDB", "MongoDB Java driver",
                versionOf(com.mongodb.client.MongoClient.class)));
        return List.copyOf(clients);
    }

    /** One line summarising all of them, for a status or footer label. */
    static String summary() {
        List<String> parts = new ArrayList<>();
        for (Client c : all()) parts.add(c.describe());
        return String.join(" · ", parts);
    }

    /**
     * The {@code Implementation-Version} from the jar the class was loaded from. Empty when the
     * class came from a directory or a jar without that manifest entry — as happens in a dev build
     * — which is why callers must tolerate a blank version rather than showing "null".
     */
    private static String versionOf(Class<?> type) {
        Package pkg = type.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null ? "" : version;
    }
}
