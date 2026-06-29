package com.nexuslink.ui.files;

/**
 * Lifecycle of a single {@link TransferItem} as it moves through the {@link TransferQueue}.
 * QUEUED → ACTIVE → (DONE | SKIPPED | FAILED).
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
    FAILED;

    /** True once the item has reached a terminal state (no further processing). */
    public boolean terminal() {
        return this == DONE || this == SKIPPED || this == FAILED;
    }
}
