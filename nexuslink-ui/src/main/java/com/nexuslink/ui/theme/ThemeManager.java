package com.nexuslink.ui.theme;

import javafx.scene.Scene;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Central theme controller. Every {@link Scene} is registered with the set of component
 * stylesheets it needs; the manager prepends the active palette ({@code theme-dark.css} or
 * {@code theme-light.css}) so that one toggle re-themes every open window live.
 *
 * <p>All structural rules live in {@code theme-base.css} and reference {@code -nl-*} looked-up
 * colors; only the palette file changes between themes. The choice is persisted via the
 * Preferences API so it survives restarts.
 */
public final class ThemeManager {

    /** Available themes; {@link #cssPath()} points at the palette stylesheet. */
    public enum Theme {
        DARK("Dark", "/com/nexuslink/ui/css/theme-dark.css"),
        LIGHT("Light", "/com/nexuslink/ui/css/theme-light.css");

        private final String label;
        private final String css;
        Theme(String label, String css) { this.label = label; this.css = css; }
        public String label() { return label; }
        public String cssPath() { return css; }
        public Theme other() { return this == DARK ? LIGHT : DARK; }
    }

    private static final String BASE_CSS = "/com/nexuslink/ui/css/theme-base.css";
    private static final String PREF_KEY = "ui.theme";

    private static final ThemeManager INSTANCE = new ThemeManager();
    public static ThemeManager get() { return INSTANCE; }

    private final Preferences prefs = Preferences.userRoot().node("com/nexuslink");
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private Theme current;

    private ThemeManager() {
        Theme saved;
        try {
            saved = Theme.valueOf(prefs.get(PREF_KEY, Theme.DARK.name()));
        } catch (IllegalArgumentException e) {
            saved = Theme.DARK;
        }
        current = saved;
    }

    public Theme current() { return current; }

    /**
     * Register a scene to be themed. {@code componentCssPaths} are this scene's own stylesheets
     * (e.g. {@code rest-client.css}); the base + active palette are added automatically and the
     * scene is themed immediately.
     */
    public void register(Scene scene, String... componentCssPaths) {
        Objects.requireNonNull(scene, "scene");
        Registration reg = new Registration(scene, componentCssPaths);
        registrations.add(reg);
        applyTo(reg);
    }

    /** Switch to a specific theme (persisted) and re-theme every live scene. */
    public void set(Theme theme) {
        if (theme == null || theme == current) return;
        current = theme;
        prefs.put(PREF_KEY, current.name());
        applyToAll();
        listeners.forEach(Runnable::run);
    }

    /** Flip dark/light. Returns the new theme. */
    public Theme toggle() {
        set(current.other());
        return current;
    }

    /** Notified after every theme change (e.g. to refresh a menu label). */
    public void addListener(Runnable l) { if (l != null) listeners.add(l); }

    private void applyToAll() {
        Iterator<Registration> it = registrations.iterator();
        while (it.hasNext()) {
            Registration reg = it.next();
            if (reg.scene.get() == null) { it.remove(); continue; }   // prune collected scenes
            applyTo(reg);
        }
    }

    private void applyTo(Registration reg) {
        Scene scene = reg.scene.get();
        if (scene == null) return;
        List<String> sheets = new ArrayList<>();
        sheets.add(url(current.cssPath()));   // palette first
        sheets.add(url(BASE_CSS));            // structural rules
        for (String c : reg.components) sheets.add(url(c));
        scene.getStylesheets().setAll(sheets);
    }

    private String url(String path) {
        return Objects.requireNonNull(getClass().getResource(path),
                "Missing stylesheet: " + path).toExternalForm();
    }

    private static final class Registration {
        final WeakReference<Scene> scene;
        final String[] components;
        Registration(Scene scene, String[] components) {
            this.scene = new WeakReference<>(scene);
            this.components = components == null ? new String[0] : components.clone();
        }
    }
}
