package com.nexuslink.ui.util;

import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The application icon — the NexusLink mark, rendered from {@code logo.svg} to PNG at the sizes a
 * window manager, task bar and alt-tab switcher pick from. JavaFX cannot load SVG, so the PNGs are
 * the shipped artefact; regenerate them from
 * {@code com/nexuslink/ui/images/logo.svg} if the mark ever changes.
 *
 * <p>Loaded once and shared: every {@link Stage} handed to {@link #apply(Stage)} gets the same
 * images, so opening a dialog costs nothing.
 */
public final class AppIcons {

    private static final String BASE = "/com/nexuslink/ui/images/icon-";
    private static final int[] SIZES = {16, 32, 48, 64, 128, 256, 512};

    private static List<Image> icons;

    private AppIcons() {}

    /** The icon at every bundled size, largest last. Empty if the resources are missing. */
    public static synchronized List<Image> icons() {
        if (icons != null) return icons;
        List<Image> loaded = new ArrayList<>();
        for (int size : SIZES) {
            try (InputStream in = AppIcons.class.getResourceAsStream(BASE + size + ".png")) {
                if (in != null) loaded.add(new Image(in));
            } catch (Exception ignored) {
                // A missing or unreadable icon must never stop a window from opening.
            }
        }
        icons = List.copyOf(loaded);
        return icons;
    }

    /** Puts the application icon on {@code stage}. Does nothing when the stage is null. */
    public static void apply(Stage stage) {
        if (stage == null) return;
        List<Image> images = icons();
        if (!images.isEmpty()) stage.getIcons().setAll(images);
    }
}
