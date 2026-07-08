package com.nexuslink.protocol.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns one {@link HikariDataSource} per connection profile and hands out pooled connections.
 * A "profile" here is identified by a caller-supplied key (see {@link #keyFor(String, String)}),
 * so two SQL tabs pointed at the same URL/user share a single pool while different targets stay
 * isolated.
 *
 * <h2>Why this works with on-demand drivers</h2>
 * The tricky part of pooling on top of the {@link JdbcDriverRegistry}/{@link DriverShim} strategy is
 * that a driver loaded from a downloaded jar lives in a child classloader that HikariCP can't see.
 * We sidestep it by <em>never</em> setting {@code driverClassName} on the {@link HikariConfig}: with
 * only a JDBC URL set, Hikari's internal data source resolves the driver through
 * {@link java.sql.DriverManager#getDriver(String)}, which finds the {@link DriverShim} that
 * {@link ExternalDriverLoader} registered against the app classloader. Bundled drivers auto-register
 * the same way via the JDBC {@code ServiceLoader} SPI. So the pooled path and the direct
 * {@code DriverManager} path resolve drivers identically.
 *
 * <p>The manager is thread-safe and {@link AutoCloseable}: {@link #close()} shuts every pool down.
 */
public final class JdbcConnectionPool implements AutoCloseable {

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final AtomicInteger poolSeq = new AtomicInteger();

    /**
     * A stable key for a connection profile. Keying on URL + user keeps pools separate per target and
     * per identity, without ever holding on to the password.
     */
    public static String keyFor(String url, String user) {
        return (user == null || user.isBlank() ? "" : user + "@") + (url == null ? "" : url);
    }

    /**
     * Returns the pool for {@code key}, creating it on first use from the given profile. Subsequent
     * calls with the same key return the existing pool and ignore the (assumed identical) profile —
     * matching Hikari's "one data source per profile" model.
     */
    public HikariDataSource dataSourceFor(String key, String url, String user, String password,
                                          Map<String, String> extraProps, JdbcPoolConfig config) {
        JdbcPoolConfig cfg = config != null ? config : JdbcPoolConfig.defaults();
        return pools.computeIfAbsent(key, k -> {
            String poolName = "nexuslink-jdbc-" + poolSeq.incrementAndGet();
            HikariConfig hc = cfg.toHikariConfig(url, user, password, extraProps, poolName);
            return new HikariDataSource(hc);
        });
    }

    /** Convenience: build (or reuse) the pool and borrow a connection from it. */
    public Connection getConnection(String key, String url, String user, String password,
                                    Map<String, String> extraProps, JdbcPoolConfig config)
            throws SQLException {
        return dataSourceFor(key, url, user, password, extraProps, config).getConnection();
    }

    /** True if a pool currently exists for {@code key}. */
    public boolean hasPool(String key) {
        return pools.containsKey(key);
    }

    /** Number of live pools managed here. */
    public int poolCount() {
        return pools.size();
    }

    /** Snapshot of the active pool keys. */
    public Set<String> keys() {
        return Set.copyOf(pools.keySet());
    }

    /** Closes and forgets the pool for {@code key} (no-op if none). Returns true if one was closed. */
    public boolean closePool(String key) {
        HikariDataSource ds = pools.remove(key);
        if (ds != null) {
            ds.close();
            return true;
        }
        return false;
    }

    /** Shuts every pool down and clears the manager. */
    @Override
    public void close() {
        for (HikariDataSource ds : pools.values()) {
            try { ds.close(); } catch (RuntimeException ignored) {}
        }
        pools.clear();
    }
}
