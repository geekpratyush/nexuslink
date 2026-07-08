package com.nexuslink.protocol.db;

import com.zaxxer.hikari.HikariConfig;

import java.util.Map;
import java.util.Properties;

/**
 * Immutable tuning for a per-profile HikariCP connection pool. Everything here has a sensible
 * default (see {@link #defaults()}), so a caller only overrides what it cares about via the
 * {@link Builder}. This type is pure — it never opens a connection — which keeps it unit-testable
 * offline and free of any JavaFX/UI coupling.
 *
 * <p>Sizes and timeouts map straight onto HikariCP's own knobs; see {@link #applyTo(HikariConfig)}.
 */
public final class JdbcPoolConfig {

    /** Hikari's own defaults, restated here so the numbers are visible and testable. */
    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    public static final int DEFAULT_MIN_IDLE = 2;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L;
    public static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000L;      // 10 min
    public static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;    // 30 min
    public static final long DEFAULT_VALIDATION_TIMEOUT_MS = 5_000L;
    public static final long DEFAULT_KEEPALIVE_MS = 0L;               // disabled unless set

    private final int maxPoolSize;
    private final int minIdle;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final long validationTimeoutMs;
    private final long keepaliveTimeMs;
    private final boolean readOnly;
    private final boolean autoCommit;
    private final String connectionTestQuery; // null → driver's isValid() (JDBC4)

    private JdbcPoolConfig(Builder b) {
        // Clamp the obvious foot-guns so a bad profile can never produce an invalid Hikari pool.
        this.maxPoolSize = Math.max(1, b.maxPoolSize);
        this.minIdle = Math.max(0, Math.min(b.minIdle, this.maxPoolSize));
        this.connectionTimeoutMs = Math.max(250L, b.connectionTimeoutMs);
        this.idleTimeoutMs = Math.max(0L, b.idleTimeoutMs);
        this.maxLifetimeMs = Math.max(0L, b.maxLifetimeMs);
        this.validationTimeoutMs = Math.max(250L, b.validationTimeoutMs);
        this.keepaliveTimeMs = Math.max(0L, b.keepaliveTimeMs);
        this.readOnly = b.readOnly;
        this.autoCommit = b.autoCommit;
        this.connectionTestQuery = b.connectionTestQuery;
    }

    /** A pool config with all-default sizing/timeouts. */
    public static JdbcPoolConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxPoolSize() { return maxPoolSize; }
    public int minIdle() { return minIdle; }
    public long connectionTimeoutMs() { return connectionTimeoutMs; }
    public long idleTimeoutMs() { return idleTimeoutMs; }
    public long maxLifetimeMs() { return maxLifetimeMs; }
    public long validationTimeoutMs() { return validationTimeoutMs; }
    public long keepaliveTimeMs() { return keepaliveTimeMs; }
    public boolean readOnly() { return readOnly; }
    public boolean autoCommit() { return autoCommit; }
    public String connectionTestQuery() { return connectionTestQuery; }

    /**
     * Copies this config's sizing/timeout knobs onto a {@link HikariConfig}. The caller is expected
     * to have already set the JDBC URL, credentials and any driver properties.
     * <p>
     * Note we deliberately do <em>not</em> touch {@code driverClassName} here: leaving it unset makes
     * Hikari resolve the driver through {@link java.sql.DriverManager}, which is what lets an
     * on-demand driver registered via a {@link DriverShim} still work (see {@link JdbcConnectionPool}).
     */
    public void applyTo(HikariConfig hc) {
        hc.setMaximumPoolSize(maxPoolSize);
        hc.setMinimumIdle(minIdle);
        hc.setConnectionTimeout(connectionTimeoutMs);
        hc.setIdleTimeout(idleTimeoutMs);
        hc.setMaxLifetime(maxLifetimeMs);
        hc.setValidationTimeout(validationTimeoutMs);
        // Keepalive must stay below maxLifetime; only enable it when it makes sense.
        if (keepaliveTimeMs > 0 && (maxLifetimeMs == 0 || keepaliveTimeMs < maxLifetimeMs)) {
            hc.setKeepaliveTime(keepaliveTimeMs);
        }
        hc.setReadOnly(readOnly);
        hc.setAutoCommit(autoCommit);
        if (connectionTestQuery != null && !connectionTestQuery.isBlank()) {
            hc.setConnectionTestQuery(connectionTestQuery);
        }
    }

    /**
     * Builds a fully-populated {@link HikariConfig} for {@code url}. Credentials and driver
     * properties (e.g. TLS settings from {@link JdbcTlsParams}) are folded into Hikari's
     * data-source properties so they reach the driver exactly as the direct-connect path does.
     */
    public HikariConfig toHikariConfig(String url, String user, String password,
                                       Map<String, String> extraProps, String poolName) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        boolean hasUser = user != null && !user.isBlank();
        if (hasUser) {
            hc.setUsername(user);
            if (password != null) hc.setPassword(password);
        }
        if (extraProps != null && !extraProps.isEmpty()) {
            Properties dsProps = new Properties();
            extraProps.forEach(dsProps::setProperty);
            hc.setDataSourceProperties(dsProps);
        }
        if (poolName != null && !poolName.isBlank()) hc.setPoolName(poolName);
        applyTo(hc);
        return hc;
    }

    public static final class Builder {
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        private int minIdle = DEFAULT_MIN_IDLE;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
        private long idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;
        private long maxLifetimeMs = DEFAULT_MAX_LIFETIME_MS;
        private long validationTimeoutMs = DEFAULT_VALIDATION_TIMEOUT_MS;
        private long keepaliveTimeMs = DEFAULT_KEEPALIVE_MS;
        private boolean readOnly = false;
        private boolean autoCommit = true;
        private String connectionTestQuery = null;

        public Builder maxPoolSize(int v) { this.maxPoolSize = v; return this; }
        public Builder minIdle(int v) { this.minIdle = v; return this; }
        public Builder connectionTimeoutMs(long v) { this.connectionTimeoutMs = v; return this; }
        public Builder idleTimeoutMs(long v) { this.idleTimeoutMs = v; return this; }
        public Builder maxLifetimeMs(long v) { this.maxLifetimeMs = v; return this; }
        public Builder validationTimeoutMs(long v) { this.validationTimeoutMs = v; return this; }
        public Builder keepaliveTimeMs(long v) { this.keepaliveTimeMs = v; return this; }
        public Builder readOnly(boolean v) { this.readOnly = v; return this; }
        public Builder autoCommit(boolean v) { this.autoCommit = v; return this; }
        public Builder connectionTestQuery(String v) { this.connectionTestQuery = v; return this; }

        public JdbcPoolConfig build() { return new JdbcPoolConfig(this); }
    }
}
