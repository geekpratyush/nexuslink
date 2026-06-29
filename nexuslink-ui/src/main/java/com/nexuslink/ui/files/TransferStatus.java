package com.nexuslink.ui.files;

/**
 * Lifecycle of a single {@link TransferItem} as it moves through the {@link TransferQueue}.
 * QUEUED → ACTIVE → (DONE | SKIPPED | FAILED | CANCELLED). A FAILED/CANCELLED item can be retried,
 * which resets it back to QUEUED.
 */
public enum TransferStatus {
    /** Waiting in the queue, not yet started. */
    QUEUED,
    /** Currently being transferred by the worker. */
    ACTIVE,
    /** Completed successfully. */
    DONE,
    /** A pre-existing target was kept (user chose Skip / Skip all). */
    SKIPPED,
    /** The transfer threw an error. */
    FAILED,
    /** The user cancelled the item before it completed. */
    CANCELLED;

    /** True once the item has reached a terminal state (no further processing). */
    public boolean terminal() {
        return this == DONE || this == SKIPPED || this == FAILED || this == CANCELLED;
    }

    /** True when a terminal item can be re-queued (it didn't already succeed). */
    public boolean retryable() {
        return this == FAILED || this == CANCELLED;
    }
}
