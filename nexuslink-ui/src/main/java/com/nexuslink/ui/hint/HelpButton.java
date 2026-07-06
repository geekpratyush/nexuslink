package com.nexuslink.ui.hint;

import com.nexuslink.ui.help.HelpDialog;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/**
 * A small circular <b>?</b> button that opens the Help dialog at a chosen anchor. Drop it into the
 * header of any panel: {@code header.getChildren().add(new HelpButton("kafka-client#producer"))}.
 * <p>
 * The {@code navigationTarget} is a Help topic id, optionally with a {@code #anchor}
 * (e.g. {@code "rest-client#auth"}), passed straight through to {@link HelpDialog#open(String)}.
 */
public final class HelpButton extends Button {

    /** @param navigationTarget topic id or {@code topicId#anchor} to open on click */
    public HelpButton(String navigationTarget) {
        this(navigationTarget, "Open help for this panel");
    }

    public HelpButton(String navigationTarget, String tooltip) {
        super("?");
        getStyleClass().add("help-button");
        setFocusTraversable(false);
        Tooltip tip = new Tooltip(tooltip + "  (F1)");
        tip.setShowDelay(Duration.millis(300));
        setTooltip(tip);
        setOnAction(e -> HelpDialog.open(navigationTarget));
    }
}
