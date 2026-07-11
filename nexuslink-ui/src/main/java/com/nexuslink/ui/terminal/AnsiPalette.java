package com.nexuslink.ui.terminal;

import javafx.scene.paint.Color;

/**
 * Maps an xterm-256 colour index to a JavaFX {@link Color}, using the standard xterm layout: indices
 * 0–15 are the 16 ANSI colours (normal + bright), 16–231 are the 6×6×6 RGB cube, and 232–255 are the
 * 24-step grayscale ramp. The bold flag brightens a normal (0–7) foreground to its bright (8–15)
 * variant, matching a typical terminal.
 */
final class AnsiPalette {

    private AnsiPalette() {}

    /** Conventional dark-terminal defaults. */
    static final Color DEFAULT_FG = Color.web("#d4d4d4");
    static final Color DEFAULT_BG = Color.web("#1e1e1e");
    static final Color CURSOR = Color.web("#d4d4d4");

    private static final int[] CUBE_LEVELS = {0, 95, 135, 175, 215, 255};

    // The 16 base ANSI colours (VS Code dark-ish palette).
    private static final Color[] BASE_16 = {
            Color.web("#000000"), Color.web("#cd3131"), Color.web("#0dbc79"), Color.web("#e5e510"),
            Color.web("#2472c8"), Color.web("#bc3fbc"), Color.web("#11a8cd"), Color.web("#e5e5e5"),
            Color.web("#666666"), Color.web("#f14c4c"), Color.web("#23d18b"), Color.web("#f5f543"),
            Color.web("#3b8eea"), Color.web("#d670d6"), Color.web("#29b8db"), Color.web("#ffffff"),
    };

    static Color foreground(int index, boolean bold) {
        if (index < 0) return DEFAULT_FG;
        if (bold && index < 8) index += 8;   // bold brightens the 8 base foreground colours
        return color(index);
    }

    static Color background(int index) {
        return index < 0 ? DEFAULT_BG : color(index);
    }

    private static Color color(int index) {
        if (index < 16) return BASE_16[index];
        if (index < 232) {
            int i = index - 16;
            int r = CUBE_LEVELS[(i / 36) % 6];
            int g = CUBE_LEVELS[(i / 6) % 6];
            int b = CUBE_LEVELS[i % 6];
            return Color.rgb(r, g, b);
        }
        int level = 8 + (index - 232) * 10;    // grayscale ramp
        level = Math.min(255, level);
        return Color.rgb(level, level, level);
    }
}
