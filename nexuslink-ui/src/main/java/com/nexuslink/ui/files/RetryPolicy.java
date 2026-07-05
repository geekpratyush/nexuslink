package com.nexuslink.ui.files;

/**
 * Auto-retry rule for transfers that fail with a transient error: how many attempts to make in
 * total and how long to back off between them (exponential, capped). JavaFX-free and immutable so
 * the {@link TransferQueue} retry path is unit-testable.
 *
 * <p>{@code maxAttempts} counts the first try, so a policy of 3 allows two retries. A policy with
 * {@code maxAttempts <= 1} disables auto-retry entirely ({@link #none()}).</p>
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final double multiplier;
    private final long maxDelayMillis;

    public RetryPolicy(int maxAttempts, long baseDelayMillis, double multiplier, long maxDelayMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMillis = Math.max(0, baseDelayMillis);
        this.multiplier = multiplier < 1 ? 1 : multiplier;
        this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
    }

    /** Auto-retry disabled: a single attempt, no backoff. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, 0, 1, 0);
    }

    /** Sensible default: 3 attempts, 500ms → 1s → … capped at 10s (×2 backoff). */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 500, 2.0, 10_000);
    }

    public int maxAttempts() { return maxAttempts; }

    /** True when auto-retry is on (more than one attempt is allowed). */
    public boolean enabled() { return maxAttempts > 1; }

    /**
     * Whether another attempt is allowed after {@code attemptsSoFar} have already been made
     * (each a started-and-failed try).
     */
    public boolean shouldRetry(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    /**
     * Backoff before the retry that follows attempt {@code attemptNumber} (1-based): the delay grows
     * as {@code base * multiplier^(attemptNumber-1)} and is clamped to {@code maxDelayMillis}.
     */
    public long backoffMillis(int attemptNumber) {
        if (attemptNumber < 1) attemptNumber = 1;
        double delay = baseDelayMillis * Math.pow(multiplier, attemptNumber - 1);
        if (delay >= maxDelayMillis) return maxDelayMillis;
        return (long) delay;
    }
}
