package com.nexuslink.ui.env;

import com.nexuslink.core.di.AppContext;
import com.nexuslink.core.env.EnvironmentService;

/**
 * Small UI-side convenience for resolving {@code ${VAR}} references against the active environment
 * from any protocol view, without each view re-implementing the {@link AppContext} lookup. Resolves
 * to a no-op when no {@link EnvironmentService} is registered (e.g. in unit tests).
 */
public final class Env {

    private Env() {}

    /** The active {@link EnvironmentService}, or {@code null} if none is registered. */
    public static EnvironmentService service() {
        return AppContext.get().isRegistered(EnvironmentService.class)
                ? AppContext.get().resolve(EnvironmentService.class) : null;
    }

    /** Interpolates {@code ${VAR}} in {@code template} against the active environment, or returns it as-is. */
    public static String resolve(String template) {
        EnvironmentService s = service();
        return s == null ? template : s.interpolate(template);
    }
}
