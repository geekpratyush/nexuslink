package com.nexuslink.ui.files;

/**
 * Decides whether an interrupted transfer can pick up where it left off, given the source's size and
 * the size of whatever partial file is sitting at the destination. Pure and JavaFX-free so it is
 * unit-testable; the transports supply the sizes and act on the verdict.
 *
 * <p><strong>Resuming trusts that the bytes already at the destination are a correct prefix of the
 * source.</strong> That holds for a transfer this queue itself interrupted, but not if the source has
 * been rewritten since — appending to a stale prefix splices two different files into one whose
 * <em>size is exactly right</em>, so no size check can detect it. This is why resume is opt-in and why
 * pairing it with the queue's checksum verification is the safe combination: only a hash catches a bad
 * splice. When in doubt the plan errs toward re-sending the whole file.
 */
public final class ResumePlan {

    /** What to do with a destination file that already holds some bytes. */
    public enum Action {
        /** Send the file from the start (nothing usable at the destination, or its state is not trustworthy). */
        TRANSFER_WHOLE,
        /** Append to the destination starting at {@link Plan#offset()}. */
        RESUME_FROM,
        /** The destination already holds as many bytes as the source — nothing left to send. */
        ALREADY_COMPLETE
    }

    /** A verdict plus the byte offset to start from ({@code 0} unless the action is {@link Action#RESUME_FROM}). */
    public record Plan(Action action, long offset) {

        /** Bytes still to move, given the source size ({@code 0} when already complete). */
        public long remaining(long sourceSize) {
            return action == Action.ALREADY_COMPLETE ? 0 : Math.max(0, sourceSize - offset);
        }

        /** A short phrase for the transfer row's note, e.g. {@code "resuming at 1.4 MB"}. */
        public String summary() {
            return switch (action) {
                case RESUME_FROM -> "resuming at " + FileItem.humanSize(offset);
                case ALREADY_COMPLETE -> "already complete";
                case TRANSFER_WHOLE -> "sending whole file";
            };
        }
    }

    private static final Plan WHOLE = new Plan(Action.TRANSFER_WHOLE, 0);

    private ResumePlan() {}

    /**
     * Plans a transfer of a {@code sourceSize}-byte file onto a destination already holding
     * {@code destSize} bytes ({@code destSize <= 0} when the destination is absent or empty).
     *
     * <p>Resuming needs a partial file strictly shorter than the source: a destination that is
     * <em>longer</em> cannot be a prefix of what we are sending — it is some other file, or one written
     * by someone else — so the whole file is re-sent rather than silently producing a hybrid. An unknown
     * source size (non-positive) is likewise not enough to reason about, so it re-sends.
     */
    public static Plan of(long sourceSize, long destSize, boolean resumeEnabled) {
        if (!resumeEnabled) return WHOLE;
        if (sourceSize <= 0 || destSize <= 0) return WHOLE;
        if (destSize > sourceSize) return WHOLE;
        if (destSize == sourceSize) return new Plan(Action.ALREADY_COMPLETE, sourceSize);
        return new Plan(Action.RESUME_FROM, destSize);
    }
}
