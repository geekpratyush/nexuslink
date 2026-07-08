package com.nexuslink.core.event;

/**
 * Lifecycle state of a single logical connection, as tracked by {@link ConnectionRegistry}
 * and surfaced in the connection-state panel.
 */
public enum ConnState {
    /** Connected and currently doing work (a request/transfer/consume is in flight). */
    ACTIVE,
    /** Connected but idle — established, no work in flight. */
    IDLE,
    /** Last operation failed / the connection is in an error state. */
    FAILED,
    /** Disconnected / closed — the registry drops these from its live tally. */
    CLOSED
}
