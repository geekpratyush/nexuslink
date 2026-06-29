package com.nexuslink.protocol.rabbitmq;

/**
 * Outcome of a publisher-confirm round-trip on a {@code confirmSelect} channel. After publishing,
 * the caller waits for the broker to confirm the message; the result is one of:
 *
 * <ul>
 *   <li>{@link #ACKED} &mdash; the broker accepted (and, for persistent messages, persisted) the
 *       publish;</li>
 *   <li>{@link #NACKED} &mdash; the broker rejected the publish (e.g. an internal error), so the
 *       message was lost;</li>
 *   <li>{@link #TIMEOUT} &mdash; no confirm arrived within the wait window; the message's fate is
 *       unknown and the caller should treat it as un-confirmed.</li>
 * </ul>
 *
 * <p>This is a pure value with no broker dependency, so {@link #fromWaitForConfirms} — the mapping
 * from {@code Channel.waitForConfirms}' boolean result — is the unit-tested seam.
 */
public enum PublishConfirm {
    ACKED,
    NACKED,
    TIMEOUT;

    /**
     * Maps the boolean returned by {@code Channel.waitForConfirms(...)} to a confirm outcome:
     * {@code true} (every outstanding publish was ack'd) becomes {@link #ACKED}, {@code false} (at
     * least one was nack'd) becomes {@link #NACKED}. A {@code TimeoutException} from the wait is
     * surfaced separately as {@link #TIMEOUT} by the caller.
     */
    public static PublishConfirm fromWaitForConfirms(boolean allConfirmed) {
        return allConfirmed ? ACKED : NACKED;
    }
}
