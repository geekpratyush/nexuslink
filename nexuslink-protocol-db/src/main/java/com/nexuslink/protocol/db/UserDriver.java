package com.nexuslink.protocol.db;

/**
 * A JDBC driver the user added themselves by pointing at a jar on disk — the escape hatch for
 * databases NexusLink doesn't know about, and for organisations whose approved driver build is
 * only available from an internal share.
 *
 * <p>Mutable with a no-arg constructor because it is persisted as JSON by {@link UserDriverStore},
 * matching the on-disk model used elsewhere in the app.
 */
public final class UserDriver {

    /** Stable id, unique across the built-in catalog and the user's own drivers. */
    public String id;
    /** What the user calls it, shown in the driver list. */
    public String displayName;
    /** The {@code java.sql.Driver} implementation class inside {@link #jarPath}. */
    public String driverClass;
    /** Absolute path to the jar. Kept as a reference — the jar is not copied into the app. */
    public String jarPath;
    /** Optional template connection URL pre-filled when this driver is selected. */
    public String sampleUrl;

    public UserDriver() {}

    public UserDriver(String id, String displayName, String driverClass, String jarPath, String sampleUrl) {
        this.id = id;
        this.displayName = displayName;
        this.driverClass = driverClass;
        this.jarPath = jarPath;
        this.sampleUrl = sampleUrl;
    }

    /**
     * Adapts this to the catalog's {@link DriverInfo} shape so user drivers appear in the same
     * list as the built-in ones. Never bundled, and it has no Maven coordinates — the jar is
     * already on disk, so there is nothing to download.
     */
    public DriverInfo toDriverInfo() {
        return new DriverInfo(id, displayName, driverClass,
                sampleUrl == null ? "" : sampleUrl, false, null, false);
    }
}
