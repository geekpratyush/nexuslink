package com.nexuslink.ui.main;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Persists which protocol/connection types the user has disabled, so each user sees only the
 * connectors they actually use. Stored via the Preferences API (survives restarts). Default: all
 * enabled.
 */
final class ProtocolPrefs {

    private static final String KEY = "disabledProtocols";
    private final Preferences prefs = Preferences.userRoot().node("com/nexuslink");

    Set<String> disabled() {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : prefs.get(KEY, "").split(",")) {
            if (!id.isBlank()) ids.add(id.trim());
        }
        return ids;
    }

    boolean isEnabled(String id) { return !disabled().contains(id); }

    void setDisabled(Set<String> ids) { prefs.put(KEY, String.join(",", ids)); }
}
