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

    /**
     * Publishes a value captured at runtime — a token extracted from a response, say — so that
     * {@code ${name}} resolves to it from now on. Session-scoped: never written to the environment
     * file, because a captured credential belongs to this session only.
     */
    public static void set(String name, String value) {
        EnvironmentService s = service();
        if (s != null) s.setRuntime(name, value);
    }

    /** The values captured this session, newest wins. Empty when no environment service is registered. */
    public static java.util.Map<String, String> captured() {
        EnvironmentService s = service();
        return s == null ? java.util.Map.of() : s.runtimeVariables();
    }
}
