package com.nexuslink.core.env;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecretMaskingFilterTest {

    @Test
    void looksSecretMatchesCommonSecretNames() {
        assertTrue(SecretMaskingFilter.looksSecret("PASSWORD"));
        assertTrue(SecretMaskingFilter.looksSecret("apiKey"));
        assertTrue(SecretMaskingFilter.looksSecret("AUTH_TOKEN"));
        assertTrue(SecretMaskingFilter.looksSecret("db_passwd"));
        assertFalse(SecretMaskingFilter.looksSecret("HOST"));
        assertFalse(SecretMaskingFilter.looksSecret("baseUrl"));
        assertFalse(SecretMaskingFilter.looksSecret(null));
    }

    @Test
    void scrubReplacesEveryOccurrenceOfSecretValues() {
        var filter = new SecretMaskingFilter(List.of("s3cr3t", "tok_123"));
        String text = "Authorization: Bearer tok_123; password=s3cr3t; retry s3cr3t";
        String scrubbed = filter.scrub(text);
        assertFalse(scrubbed.contains("s3cr3t"));
        assertFalse(scrubbed.contains("tok_123"));
        assertEquals("Authorization: Bearer " + SecretMaskingFilter.MASK
                + "; password=" + SecretMaskingFilter.MASK + "; retry " + SecretMaskingFilter.MASK, scrubbed);
    }

    @Test
    void longerSecretsAreMaskedBeforeShorterOverlappingOnes() {
        // "abc123" contains "abc"; longest-first ordering must mask the full value, not a fragment.
        var filter = new SecretMaskingFilter(List.of("abc", "abc123"));
        assertEquals("key=" + SecretMaskingFilter.MASK, filter.scrub("key=abc123"));
    }

    @Test
    void emptyAndNullValuesAreIgnored() {
        var filter = new SecretMaskingFilter(java.util.Arrays.asList("", null, "real"));
        assertFalse(filter.isEmpty());
        assertEquals("x=" + SecretMaskingFilter.MASK, filter.scrub("x=real"));
        assertEquals("", SecretMaskingFilter.maskValue(""));
        assertEquals(SecretMaskingFilter.MASK, SecretMaskingFilter.maskValue("anything"));
    }

    @Test
    void forEnvironmentMasksOnlyFlaggedSecrets() {
        var env = new Environment("dev").set("USER", "alice").set("TOKEN", "xyz", true);
        var filter = SecretMaskingFilter.forEnvironment(env);
        assertEquals("alice / " + SecretMaskingFilter.MASK, filter.scrub("alice / xyz"));
    }
}
