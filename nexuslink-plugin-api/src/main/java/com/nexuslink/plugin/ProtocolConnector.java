package com.nexuslink.plugin;

import java.util.concurrent.CompletableFuture;

/**
 * SPI that all protocol implementations must satisfy.
 * Each connector is instantiated per connection profile.
 */
public interface ProtocolConnector extends AutoCloseable {

    /** Unique identifier (e.g., "rest", "kafka", "sftp"). */
    String protocolId();

    /** Human-readable name shown in the UI. */
    String displayName();

    /** Returns the descriptor for UI metadata (icon, color, category). */
    PluginDescriptor descriptor();

    /** Validates the connection profile before attempting to connect. */
    ValidationResult validate(ConnectionConfig config);

    /** Asynchronously opens a connection. Reports progress via the callback. */
    CompletableFuture<ConnectionResult> connect(ConnectionConfig config, ProgressCallback progress);

    /** Returns true if this connector currently has an active connection. */
    boolean isConnected();

    /** Called when the user disconnects. Must be idempotent. */
    @Override
    void close();

    interface ProgressCallback {
        void onStep(String stepName, StepStatus status, long elapsedMs, String detail);
    }

    enum StepStatus { PENDING, IN_PROGRESS, SUCCESS, FAILED }
}
