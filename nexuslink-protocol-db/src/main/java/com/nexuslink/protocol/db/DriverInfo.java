package com.nexuslink.protocol.db;

/**
 * Catalog entry for a JDBC driver: how to identify it, a sample connection URL, whether it
 * ships bundled with the app, and (for on-demand drivers) its Maven coordinates so the
 * driver manager can fetch it.
 */
public record DriverInfo(
        String id,                 // short id, e.g. "postgresql"
        String displayName,        // e.g. "PostgreSQL"
        String driverClass,        // JDBC Driver implementation class
        String sampleUrl,          // template connection URL
        boolean bundled,           // true → ships in the app; false → load on demand
        String mavenCoords,        // group:artifact:version for on-demand download
        boolean requiresLicenseAck // true for Oracle/DB2 etc.
) {}
