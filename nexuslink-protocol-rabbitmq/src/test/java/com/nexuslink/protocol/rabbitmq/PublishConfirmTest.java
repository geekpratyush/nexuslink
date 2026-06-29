package com.nexuslink.protocol.rabbitmq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The pure publisher-confirm outcome mapping — the broker-free seam of {@code publishConfirmed}. */
class PublishConfirmTest {

    @Test
    void allConfirmedMapsToAcked() {
        assertSame(PublishConfirm.ACKED, PublishConfirm.fromWaitForConfirms(true));
    }

    @Test
    void notAllConfirmedMapsToNacked() {
        assertSame(PublishConfirm.NACKED, PublishConfirm.fromWaitForConfirms(false));
    }

    @Test
    void timeoutIsADistinctOutcome() {
        // TIMEOUT never comes from the boolean mapping — it is surfaced from a thrown TimeoutException.
        assertEquals(3, PublishConfirm.values().length);
    }
}
