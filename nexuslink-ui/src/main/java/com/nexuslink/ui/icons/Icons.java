package com.nexuslink.ui.icons;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * NexusLink's bespoke icon set — hand-authored SVG path data on a 24×24 grid, drawn as
 * line glyphs. The set shares a deliberate "node + link" motif (small connector dots and
 * joining strokes) so it reads as one original family rather than stock clip-art.
 *
 * <p>Icons are returned as JavaFX nodes and coloured via CSS ({@code .nl-icon} / the
 * {@code .nl-icon-accent} modifier), so they follow the active theme automatically.
 */
public final class Icons {

    private Icons() {}

    /** Author grid; icons are scaled from this to the requested pixel size. */
    private static final double GRID = 24.0;

    private static final Map<String, String> PATHS = Map.ofEntries(
        // ---- Protocols ----
        Map.entry("rest",       "M12 2.5 A9.5 9.5 0 1 0 12.01 2.5 Z M2.5 12 H21.5 "
                              + "M12 2.5 C6.5 6 6.5 18 12 21.5 M12 2.5 C17.5 6 17.5 18 12 21.5"),
        Map.entry("ws",         "M3 9 H14 M11.5 6 L14.5 9 L11.5 12 M21 15 H10 M12.5 18 L9.5 15 L12.5 12"),
        Map.entry("sql",        "M4.5 6 C4.5 4 19.5 4 19.5 6 V18 C19.5 20 4.5 20 4.5 18 Z "
                              + "M4.5 6 C4.5 8 19.5 8 19.5 6 M4.5 12 C4.5 14 19.5 14 19.5 12"),
        Map.entry("mongo",      "M12 2.5 C7 7.5 7 15.5 12 21.5 C17 15.5 17 7.5 12 2.5 Z M12 5 V20"),
        Map.entry("mcp",        "M4.6 5 L12 12 M19.4 6 L12 12 M12 20 L12 12 "
                              + "M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 "
                              + "M3.2 5 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0 "
                              + "M18 6 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0 "
                              + "M10.6 20 a1.4 1.4 0 1 0 2.8 0 a1.4 1.4 0 1 0 -2.8 0"),
        Map.entry("ai",         "M12 2.5 L13.7 10.3 L21.5 12 L13.7 13.7 L12 21.5 "
                              + "L10.3 13.7 L2.5 12 L10.3 10.3 Z"),

        // ---- Resource / object types (explorer) ----
        Map.entry("server",     "M4 5 H20 V9 H4 Z M4 12 H20 V16 H4 Z "
                              + "M6 6 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 "
                              + "M6 13 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0"),
        Map.entry("database",   "M4.5 6 C4.5 4 19.5 4 19.5 6 V18 C19.5 20 4.5 20 4.5 18 Z "
                              + "M4.5 6 C4.5 8 19.5 8 19.5 6 M4.5 12 C4.5 14 19.5 14 19.5 12"),
        Map.entry("schema",     "M3 6.5 H9 L11 8.5 H21 V18.5 H3 Z M3 11 H21"),
        Map.entry("table",      "M4 5 H20 V19 H4 Z M4 9.5 H20 M4 14.5 H20 M9.3 5 V19 M14.6 5 V19"),
        Map.entry("column",     "M9.5 5 H14.5 V19 H9.5 Z M4 5 V19 M20 5 V19 M4 5 H4.01 M20 5 H20.01"),
        Map.entry("collection", "M12 3.5 L20 7.5 L12 11.5 L4 7.5 Z M4 12 L12 16 L20 12 M4 16.5 L12 20.5 L20 16.5"),
        Map.entry("index",      "M13 3 L6 13 H11 L10 21 L18 10 H13 Z"),
        Map.entry("field",      "M6 8 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 M10 8 H19 M6 13 H19 M6 17 H15"),

        // ---- Messaging (ready for Kafka / MQ connectors) ----
        Map.entry("topic",      "M6 18 a8 8 0 0 1 8 -8 M6 18 a4 4 0 0 1 4 -4 "
                              + "M5 18 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 M14 6 H20 M14 9 H18"),
        Map.entry("queue",      "M9 7 H20 M9 12 H20 M9 17 H20 "
                              + "M4 6 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 "
                              + "M4 11 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 "
                              + "M4 16 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0"),
        Map.entry("queue-manager", "M4 4 H20 V8 H4 Z M4 10 H20 V14 H4 Z M4 16 H20 V20 H4 Z "
                              + "M6 6 a0.8 0.8 0 1 0 1.6 0 a0.8 0.8 0 1 0 -1.6 0"),

        // ---- Menus ----
        Map.entry("file",       "M7 3 H14 L19 8 V21 H7 Z M14 3 V8 H19"),
        Map.entry("edit",       "M5 19 V15 L15 5 L19 9 L9 19 Z M13.5 6.5 L17.5 10.5"),
        Map.entry("view",       "M2.5 12 C6 6.5 18 6.5 21.5 12 C18 17.5 6 17.5 2.5 12 Z "
                              + "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0"),
        Map.entry("tools",      "M20 5 L15.8 9.2 a2.8 2.8 0 0 1 -4 4 L5 20 L4 19 "
                              + "L11.8 11.8 a2.8 2.8 0 0 1 4 -4 Z"),
        Map.entry("connection", "M9 15 L15 9 M8.5 11 L6.5 13 a3.5 3.5 0 0 0 5 5 L13.5 16 "
                              + "M15.5 13 L17.5 11 a3.5 3.5 0 0 0 -5 -5 L10.5 8"),
        Map.entry("help",       "M12 2.5 A9.5 9.5 0 1 0 12.01 2.5 Z "
                              + "M9.2 9.2 a3 3 0 0 1 5.7 1.1 c0 2 -2.9 2.4 -2.9 4 "
                              + "M12 17.2 a0.4 0.4 0 1 0 0.01 0"),

        Map.entry("dot",        "M12 9 a3 3 0 1 0 0.01 0 Z")
    );

    /** A 16px themed icon node for {@code name} (falls back to a dot). */
    public static Node of(String name) { return of(name, 16); }

    /** A themed icon node for {@code name} scaled to {@code size} px. */
    public static Node of(String name, double size) {
        SVGPath p = new SVGPath();
        p.setContent(PATHS.getOrDefault(name, PATHS.get("dot")));
        p.getStyleClass().add("nl-icon");
        double s = size / GRID;
        p.setScaleX(s);
        p.setScaleY(s);
        return new Group(p);   // Group reports the scaled bounds, so it lays out at `size`
    }

    /** Like {@link #of(String, double)} but stroked in the accent colour. */
    public static Node accent(String name, double size) {
        Node n = of(name, size);
        ((Group) n).getChildren().get(0).getStyleClass().add("nl-icon-accent");
        return n;
    }

    public static boolean has(String name) { return PATHS.containsKey(name); }
}
