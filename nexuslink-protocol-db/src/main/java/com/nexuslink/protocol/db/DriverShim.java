package com.nexuslink.protocol.db;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Wraps a {@link Driver} that was loaded by a child {@code URLClassLoader} so that
 * {@link java.sql.DriverManager} will actually use it.
 * <p>
 * The reason this class exists: {@code DriverManager} only consults drivers whose class
 * was loaded by the same classloader as the calling code (or an ancestor). A driver
 * instantiated from an externally-downloaded jar lives in a child classloader, so
 * {@code DriverManager} silently ignores it. Registering this shim — which IS loaded by
 * the app classloader — and delegating every call to the real driver fixes that.
 */
public final class DriverShim implements Driver {

    private final Driver delegate;

    public DriverShim(Driver delegate) {
        this.delegate = delegate;
    }

    @Override public Connection connect(String url, Properties info) throws SQLException { return delegate.connect(url, info); }
    @Override public boolean acceptsURL(String url) throws SQLException { return delegate.acceptsURL(url); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException { return delegate.getPropertyInfo(url, info); }
    @Override public int getMajorVersion() { return delegate.getMajorVersion(); }
    @Override public int getMinorVersion() { return delegate.getMinorVersion(); }
    @Override public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
}
