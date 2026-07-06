package com.nexuslink.ui.hint;

import com.nexuslink.ui.icons.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * A friendly "nothing here yet" placeholder for an empty panel: a large muted icon, a headline, a
 * one-line hint, and an optional call-to-action button. Use it wherever a table/list/result area
 * would otherwise be blank so first-time users know what to do next.
 * <pre>{@code
 *   resultPane.setCenter(EmptyState.of("rest", "No response yet",
 *           "Enter a URL and press Send (Ctrl+Enter) to make your first request."));
 * }</pre>
 */
public final class EmptyState {

    private EmptyState() {}

    /** Icon + headline + hint, no action. */
    public static VBox of(String icon, String headline, String hint) {
        return build(icon, headline, hint, null, null);
    }

    /** Icon + headline + hint + a primary action button. */
    public static VBox withAction(String icon, String headline, String hint,
                                  String actionLabel, Runnable action) {
        return build(icon, headline, hint, actionLabel, action);
    }

    private static VBox build(String icon, String headline, String hint,
                              String actionLabel, Runnable action) {
        VBox box = new VBox(10);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);

        if (icon != null && Icons.has(icon)) {
            Node glyph = Icons.of(icon, 40);
            glyph.getStyleClass().add("empty-state-icon");
            box.getChildren().add(glyph);
        }

        Label title = new Label(headline);
        title.getStyleClass().add("empty-state-title");
        box.getChildren().add(title);

        if (hint != null && !hint.isBlank()) {
            Label sub = new Label(hint);
            sub.getStyleClass().add("empty-state-hint");
            sub.setWrapText(true);
            sub.setMaxWidth(360);
            sub.setAlignment(Pos.CENTER);
            box.getChildren().add(sub);
        }

        if (actionLabel != null && action != null) {
            Button cta = new Button(actionLabel);
            cta.getStyleClass().add("btn-primary");
            cta.setOnAction(e -> action.run());
            box.getChildren().add(cta);
        }
        return box;
    }
}
