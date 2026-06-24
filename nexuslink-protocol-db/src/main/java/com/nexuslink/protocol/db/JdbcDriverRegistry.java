package com.nexuslink.protocol.db;

import java.util.List;
import java.util.Optional;

/**
 * Catalog of known JDBC drivers — the bundled set (always available) plus the on-demand
 * set (loaded from a user-supplied or downloaded jar). Mirrors the DBeaver/DataGrip model:
 * the app stays small, and heavy/licensed drivers are added when needed.
 *
 * <p>See TASKS.md §8.1.1 and Decisions Log #9 for the bundling rationale.
 */
public final class JdbcDriverRegistry {

    private JdbcDriverRegistry() {}

    private static final List<DriverInfo> DRIVERS = List.of(
            // ---- Bundled (ship in the app) ----
            new DriverInfo("sqlite", "SQLite", "org.sqlite.JDBC",
                    "jdbc:sqlite:/path/to/database.db", true,
                    "org.xerial:sqlite-jdbc", false),
            new DriverInfo("h2", "H2", "org.h2.Driver",
                    "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", true,
                    "com.h2database:h2", false),
            new DriverInfo("postgresql", "PostgreSQL", "org.postgresql.Driver",
                    "jdbc:postgresql://localhost:5432/postgres", true,
                    "org.postgresql:postgresql", false),
            new DriverInfo("mysql", "MySQL", "com.mysql.cj.jdbc.Driver",
                    "jdbc:mysql://localhost:3306/mysql", true,
                    "com.mysql:mysql-connector-j", false),
            new DriverInfo("mariadb", "MariaDB", "org.mariadb.jdbc.Driver",
                    "jdbc:mariadb://localhost:3306/test", true,
                    "org.mariadb.jdbc:mariadb-java-client", false),
            // CockroachDB speaks the PostgreSQL wire protocol — reuse the bundled pg driver.
            new DriverInfo("cockroachdb", "CockroachDB (via PostgreSQL)", "org.postgresql.Driver",
                    "jdbc:postgresql://localhost:26257/defaultdb?sslmode=disable", true,
                    "org.postgresql:postgresql", false),

            // ---- On-demand (user supplies / app downloads the jar) ----
            new DriverInfo("oracle", "Oracle", "oracle.jdbc.OracleDriver",
                    "jdbc:oracle:thin:@//localhost:1521/ORCLPDB1", false,
                    "com.oracle.database.jdbc:ojdbc11:23.4.0.24.05", true),
            new DriverInfo("sqlserver", "Microsoft SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                    "jdbc:sqlserver://localhost:1433;databaseName=master;encrypt=false", false,
                    "com.microsoft.sqlserver:mssql-jdbc:12.6.1.jre11", false),
            new DriverInfo("db2", "IBM DB2", "com.ibm.db2.jcc.DB2Driver",
                    "jdbc:db2://localhost:50000/sample", false,
                    "com.ibm.db2:jcc:11.5.9.0", true),
            new DriverInfo("snowflake", "Snowflake", "net.snowflake.client.jdbc.SnowflakeDriver",
                    "jdbc:snowflake://<account>.snowflakecomputing.com/?db=<db>", false,
                    "net.snowflake:snowflake-jdbc:3.16.1", false),
            new DriverInfo("clickhouse", "ClickHouse", "com.clickhouse.jdbc.ClickHouseDriver",
                    "jdbc:ch://localhost:8123/default", false,
                    "com.clickhouse:clickhouse-jdbc:0.6.0", false)
    );

    public static List<DriverInfo> all() {
        return DRIVERS;
    }

    public static Optional<DriverInfo> byId(String id) {
        return DRIVERS.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    /** True if the driver's class can be loaded right now (bundled, or already loaded on demand). */
    public static boolean isAvailable(DriverInfo driver) {
        return isDriverLoaded(driver.driverClass());
    }

    /** True if a driver implementation class is resolvable on the current classpath. */
    public static boolean isDriverLoaded(String driverClass) {
        try {
            Class.forName(driverClass);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
