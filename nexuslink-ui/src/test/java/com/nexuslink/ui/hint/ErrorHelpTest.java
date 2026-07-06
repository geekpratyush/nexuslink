package com.nexuslink.ui.hint;

import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class ErrorHelpTest {

    @Test
    void mapsConnectionRefused() {
        assertEquals("troubleshooting#connection-refused",
                ErrorHelp.targetFor("java.net.ConnectException: Connection refused"));
    }

    @Test
    void mapsTlsHandshake() {
        assertEquals("troubleshooting#ssl-tls-handshake-failed",
                ErrorHelp.targetFor("PKIX path building failed: unable to find valid certification path"));
    }

    @Test
    void mapsAuthFailures() {
        assertEquals("troubleshooting#401-403-auth-failed-",
                ErrorHelp.targetFor("HTTP 401 Unauthorized"));
        assertEquals("troubleshooting#401-403-auth-failed-",
                ErrorHelp.targetFor("403 Forbidden"));
    }

    @Test
    void mapsTimeout() {
        assertEquals("troubleshooting#timeout", ErrorHelp.targetFor("Read timed out"));
    }

    @Test
    void mapsVaultLocked() {
        assertEquals("troubleshooting#vault-locked", ErrorHelp.targetFor("Vault locked — unlock first"));
    }

    @Test
    void unknownErrorHasNoHelp() {
        assertNull(ErrorHelp.targetFor("some totally unrelated message"));
        assertFalse(ErrorHelp.hasHelp("some totally unrelated message"));
        assertNull(ErrorHelp.targetFor(null));
        assertNull(ErrorHelp.targetFor("   "));
    }

    @Test
    void slugMatchesRendererFormat() {
        // Mirrors HelpDialog: text.toLowerCase().replaceAll("[^a-z0-9]+", "-")
        assertEquals("ssl-tls-handshake-failed", ErrorHelp.slug("SSL / TLS handshake failed"));
        assertEquals("connection-refused", ErrorHelp.slug("Connection refused"));
    }

    @Test
    void onboardingPrefsRoundTrip() throws Exception {
        Preferences node = Preferences.userRoot().node("com/nexuslink/test-onboarding-" + System.nanoTime());
        OnboardingPrefs prefs = new OnboardingPrefs(node);
        assertTrue(prefs.shouldShowOnStartup());
        assertFalse(prefs.isDismissed());
        prefs.markDismissed();
        assertTrue(prefs.isDismissed());
        assertFalse(prefs.shouldShowOnStartup());
        prefs.reset();
        assertTrue(prefs.shouldShowOnStartup());
        node.removeNode();
    }
}
