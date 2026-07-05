package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void noneDisablesRetry() {
        RetryPolicy p = RetryPolicy.none();
        assertFalse(p.enabled());
        assertEquals(1, p.maxAttempts());
        assertFalse(p.shouldRetry(1));
    }

    @Test
    void defaultPolicyAllowsTwoRetries() {
        RetryPolicy p = RetryPolicy.defaultPolicy();
        assertTrue(p.enabled());
        assertEquals(3, p.maxAttempts());
        assertTrue(p.shouldRetry(1));
        assertTrue(p.shouldRetry(2));
        assertFalse(p.shouldRetry(3));
    }

    @Test
    void backoffGrowsExponentiallyThenCaps() {
        RetryPolicy p = new RetryPolicy(5, 100, 2.0, 500);
        assertEquals(100, p.backoffMillis(1));
        assertEquals(200, p.backoffMillis(2));
        assertEquals(400, p.backoffMillis(3));
        assertEquals(500, p.backoffMillis(4));   // 800 clamped to 500
        assertEquals(500, p.backoffMillis(5));
    }

    @Test
    void invalidArgumentsAreNormalised() {
        RetryPolicy p = new RetryPolicy(0, -50, 0.5, -1);
        assertEquals(1, p.maxAttempts());         // floored at 1
        assertFalse(p.enabled());
        assertEquals(0, p.backoffMillis(1));      // base floored at 0
    }
}
