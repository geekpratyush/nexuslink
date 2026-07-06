package com.nexuslink.ui.hint;

import com.nexuslink.ui.help.HelpDialog;
import javafx.scene.control.Hyperlink;

/**
 * A "What does this mean?" hyperlink shown next to an error message. When clicked it opens the Help
 * dialog at the troubleshooting anchor that best matches the error text (see {@link ErrorHelp}).
 * <p>
 * If no specific anchor matches the error it stays hidden (and takes no layout space), so callers can
 * add it unconditionally next to any error label.
 */
public final class ErrorHelpLink extends Hyperlink {

    public ErrorHelpLink() {
        super("What does this mean?");
        getStyleClass().add("error-help-link");
        setFocusTraversable(false);
        managedProperty().bind(visibleProperty());
        setVisible(false);
    }

    /** Convenience: build a link already pointed at {@code errorText}. */
    public static ErrorHelpLink forError(String errorText) {
        ErrorHelpLink link = new ErrorHelpLink();
        link.showFor(errorText);
        return link;
    }

    /**
     * Point the link at the help for {@code errorText}. Shows the link (and wires the click) when a
     * matching anchor exists; hides it otherwise. Pass {@code null} to reset/hide.
     */
    public void showFor(String errorText) {
        String target = ErrorHelp.targetFor(errorText);
        if (target == null) {
            setVisible(false);
            setOnAction(null);
        } else {
            setVisible(true);
            setOnAction(e -> { HelpDialog.open(target); setVisited(false); });
        }
    }
}
