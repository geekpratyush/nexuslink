package com.nexuslink.ui.files;

/**
 * Free/total capacity of the volume backing a directory, for the commander's per-pane status line.
 * {@code freeBytes} is the space actually usable by the app (not just unallocated), {@code totalBytes}
 * the volume size. Purely a value + formatter; {@link FileSystem#diskSpace(String)} produces it.
 */
public record DiskSpace(long freeBytes, long totalBytes) {

    /** Fraction of the volume that is free, in [0, 1]; 0 when the total is unknown/zero. */
    public double freeFraction() {
        return totalBytes <= 0 ? 0 : Math.max(0, Math.min(1, (double) freeBytes / totalBytes));
    }

    /**
     * A compact status-line summary, e.g. {@code "15.2 GB free of 512 GB (3%)"}. Omits the percentage
     * when the total is unknown ({@code <= 0}), showing just the free figure.
     */
    public String summary() {
        String free = FileItem.humanSize(freeBytes);
        if (totalBytes <= 0) return free + " free";
        long pct = Math.round(freeFraction() * 100);
        return free + " free of " + FileItem.humanSize(totalBytes) + " (" + pct + "%)";
    }
}
