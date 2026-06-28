package com.nexuslink.ui.settings;

import com.nexuslink.core.config.SettingsService;
import com.nexuslink.core.di.AppContext;

/**
 * UI-side convenience for reaching the shared {@link SettingsService} from any
 * view, mirroring {@code Env} and {@code Metrics}. Unlike those (which no-op when
 * unregistered), settings are always needed, so this lazily registers a single
 * default instance the first time it is asked for.
 */
public final class Settings {

    private Settings() {}

    /** The shared {@link SettingsService}, creating and registering one on first use. */
    public static SettingsService service() {
        AppContext ctx = AppContext.get();
        if (!ctx.isRegistered(SettingsService.class)) {
            ctx.registerInstance(SettingsService.class, new SettingsService());
        }
        return ctx.resolve(SettingsService.class);
    }
}
