package com.nexuslink.ui.hint;

import java.util.prefs.Preferences;

/**
 * Persists whether the first-run onboarding overlay has been dismissed, so it appears only once
 * (unless the user re-opens it from Help). Stored via the Preferences API, matching
 * {@code ProtocolPrefs}. JavaFX-free so it can be unit-tested.
 */
public final class OnboardingPrefs {

    private static final String KEY = "onboardingDismissed";
    private final Preferences prefs;

    public OnboardingPrefs() {
        this(Preferences.userRoot().node("com/nexuslink"));
    }

    /** Test seam: inject a preferences node (e.g. a throwaway node). */
    OnboardingPrefs(Preferences prefs) {
        this.prefs = prefs;
    }

    /** True once the user has completed or explicitly dismissed onboarding. */
    public boolean isDismissed() {
        return prefs.getBoolean(KEY, false);
    }

    /** Should the first-run overlay auto-appear on startup? */
    public boolean shouldShowOnStartup() {
        return !isDismissed();
    }

    /** Record that onboarding is done — it won't auto-appear again. */
    public void markDismissed() {
        prefs.putBoolean(KEY, true);
    }

    /** Reset so the overlay shows again next launch (used by "Show welcome tour" in Help). */
    public void reset() {
        prefs.putBoolean(KEY, false);
    }
}
