package com.nexuslink.protocol.s3;

/**
 * Pure, SDK-free plan for splitting an upload into S3 multipart parts.
 *
 * <p>S3 constrains multipart uploads in three ways: every part except the last must be at least
 * 5 MiB, no part may exceed 5 GiB, and an upload may not exceed 10,000 parts. A caller states the
 * size it would <em>like</em> parts to be; this class clamps that into the legal range and grows it
 * when the preferred size would overflow the part limit, so the resulting plan is always uploadable.
 *
 * <p>Below the threshold the plan is a single part, which the service turns into a plain
 * {@code PutObject} — multipart only pays for itself on large objects.
 */
public record MultipartPlan(long totalSize, long partSize, int partCount) {

    /** Smallest legal size for any part but the last. */
    public static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    /** Largest legal size for a single part. */
    public static final long MAX_PART_SIZE = 5L * 1024 * 1024 * 1024;
    /** Most parts one multipart upload may have. */
    public static final int MAX_PARTS = 10_000;
    /** Largest object S3 accepts (5 TiB). */
    public static final long MAX_OBJECT_SIZE = 5L * 1024 * 1024 * 1024 * 1024;

    /** Default size at or above which an upload switches to multipart (matches the AWS CLI). */
    public static final long DEFAULT_THRESHOLD = 8L * 1024 * 1024;
    /** Default preferred part size (matches the AWS CLI). */
    public static final long DEFAULT_PART_SIZE = 8L * 1024 * 1024;

    /** Plans {@code totalSize} bytes with the default threshold and part size. */
    public static MultipartPlan of(long totalSize) {
        return of(totalSize, DEFAULT_THRESHOLD, DEFAULT_PART_SIZE);
    }

    /**
     * Plans {@code totalSize} bytes, going multipart once the size reaches {@code threshold}.
     * {@code preferredPartSize} is clamped into S3's legal range and then grown, if need be, so the
     * part count stays within {@link #MAX_PARTS}.
     */
    public static MultipartPlan of(long totalSize, long threshold, long preferredPartSize) {
        if (totalSize < 0) throw new IllegalArgumentException("negative size: " + totalSize);
        if (totalSize > MAX_OBJECT_SIZE) {
            throw new IllegalArgumentException("object exceeds S3's 5 TiB limit: " + totalSize);
        }
        if (totalSize < Math.max(threshold, 1)) {
            return new MultipartPlan(totalSize, totalSize, 1); // single PutObject, incl. a 0-byte file
        }
        long partSize = Math.min(Math.max(preferredPartSize, MIN_PART_SIZE), MAX_PART_SIZE);
        if (ceilDiv(totalSize, partSize) > MAX_PARTS) {
            // Round up to a whole MiB so the grown size stays a tidy number rather than an odd remainder.
            long needed = ceilDiv(totalSize, MAX_PARTS);
            partSize = Math.min(ceilDiv(needed, 1024 * 1024) * 1024 * 1024, MAX_PART_SIZE);
        }
        return new MultipartPlan(totalSize, partSize, (int) ceilDiv(totalSize, partSize));
    }

    /** Whether this upload should be sent as a multipart upload rather than a single PutObject. */
    public boolean multipart() { return partCount > 1; }

    /** Byte offset into the source at which part {@code partNumber} (1-based) starts. */
    public long offsetOf(int partNumber) {
        checkPart(partNumber);
        return (partNumber - 1) * partSize;
    }

    /** Length of part {@code partNumber} (1-based); the last part is whatever remains. */
    public long sizeOf(int partNumber) {
        checkPart(partNumber);
        return Math.min(partSize, totalSize - offsetOf(partNumber));
    }

    private void checkPart(int partNumber) {
        if (partNumber < 1 || partNumber > partCount) {
            throw new IllegalArgumentException("part " + partNumber + " out of range 1.." + partCount);
        }
    }

    private static long ceilDiv(long a, long b) { return (a + b - 1) / b; }
}
