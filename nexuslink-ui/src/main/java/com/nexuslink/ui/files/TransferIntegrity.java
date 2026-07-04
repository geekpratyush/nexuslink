package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies a completed transfer by comparing the source against what actually landed on the
 * destination: the byte count must match, and — when the caller can supply a checksum for both sides
 * — the digests must match too. Kept pure and JavaFX-free (the caller does the stat/hash I/O and
 * hands the plain values in), so the policy is unit-testable and reusable by {@link TransferQueue}
 * for a post-transfer check. Mirrors {@link DirectoryDiff} and {@link SyncPlanner}.
 */
public final class TransferIntegrity {

    /** A specific way a transfer failed verification. */
    public enum Issue {
        /** The destination file is not present at all. */
        DESTINATION_MISSING,
        /** The destination size differs from the source size. */
        SIZE_MISMATCH,
        /** Both checksums were supplied and they differ. */
        CHECKSUM_MISMATCH
    }

    /** The verdict for one file. {@code verified} is true exactly when {@code issues} is empty. */
    public record Report(boolean verified, long expectedSize, long actualSize,
                         String expectedHash, String actualHash, List<Issue> issues) {}

    private TransferIntegrity() {}

    /** Size-only verification (no checksum available). */
    public static Report verify(long expectedSize, Long actualSize) {
        return verify(expectedSize, actualSize, null, null);
    }

    /**
     * Verifies a transfer. {@code actualSize} is null when the destination file is missing. A checksum
     * comparison only runs when <em>both</em> {@code expectedHash} and {@code actualHash} are non-blank
     * (so a caller that could not hash one side simply skips that check); hashes compare
     * case-insensitively after trimming, matching typical hex/base64 digest formatting.
     */
    public static Report verify(long expectedSize, Long actualSize, String expectedHash, String actualHash) {
        List<Issue> issues = new ArrayList<>();
        long actual = actualSize == null ? -1 : actualSize;

        if (actualSize == null) {
            issues.add(Issue.DESTINATION_MISSING);
        } else if (actualSize != expectedSize) {
            issues.add(Issue.SIZE_MISMATCH);
        }

        if (isSet(expectedHash) && isSet(actualHash)
                && !expectedHash.trim().equalsIgnoreCase(actualHash.trim())) {
            issues.add(Issue.CHECKSUM_MISMATCH);
        }

        return new Report(issues.isEmpty(), expectedSize, actual, expectedHash, actualHash, List.copyOf(issues));
    }

    private static boolean isSet(String s) { return s != null && !s.isBlank(); }
}
