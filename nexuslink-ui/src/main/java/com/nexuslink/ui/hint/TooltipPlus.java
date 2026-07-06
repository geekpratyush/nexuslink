package com.nexuslink.ui.hint;

import com.nexuslink.ui.help.HelpDialog;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;

/**
 * "Tooltip-plus": attaches a richer hover tooltip to a field <em>and</em> wires <kbd>F1</kbd>
 * (while the field is focused) to open contextual help. The tooltip shows the field's purpose plus a
 * {@code Press F1 for more} affordance, so discovery works by hover <em>or</em> keyboard.
 * <p>
 * Usage:
 * <pre>{@code
 *   TooltipPlus.attach(bootstrapField,
 *       "Comma-separated host:port list of Kafka brokers to connect to.",
 *       "kafka-client#connection");
 * }</pre>
 */
public final class TooltipPlus {

    private TooltipPlus() {}

    /**
     * @param control     the field/control to annotate
     * @param purpose     one-line explanation shown on hover
     * @param helpTarget  Help topic id (optionally {@code topicId#anchor}) opened on F1; may be null
     *                    to show the tooltip only
     * @return the same {@code control}, for chaining
     */
    public static <T extends Control> T attach(T control, String purpose, String helpTarget) {
        String text = helpTarget == null ? purpose : purpose + "\n\nPress F1 for more.";
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(Duration.millis(400));
        tip.setHideDelay(Duration.millis(120));
        tip.setWrapText(true);
        tip.setMaxWidth(320);
        tip.getStyleClass().add("tooltip-plus");
        control.setTooltip(tip);
        if (helpTarget != null) wireF1(control, helpTarget);
        return control;
    }

    /** Shorthand for a purpose-only tooltip (no F1 target). */
    public static <T extends Control> T attach(T control, String purpose) {
        return attach(control, purpose, null);
    }

    /** Wire F1 on any node (even a non-Control container) to open contextual help. */
    public static void wireF1(Node node, String helpTarget) {
        node.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F1) {
                HelpDialog.open(helpTarget);
                e.consume();
            }
        });
    }
}
