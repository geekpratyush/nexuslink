package com.nexuslink.ui.files;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The quick-view / edit-in-place dialog: reads a file's bytes off the FX thread (up to a size cap) and
 * shows them as either an editable text area or a read-only image preview, per {@link QuickView}. For a
 * text file the <b>Save</b> button writes the edited content back through the file system
 * ({@link FileSystem#writeFile}), giving download → edit → upload-on-save for a remote file with no temp
 * files. Blocking reads/writes never touch the FX thread; failures surface in the dialog's status line.
 */
public final class QuickViewDialog {

    private QuickViewDialog() {}

    /**
     * Opens the dialog for {@code item} on {@code fs}. Non-blocking: the load runs in the background and
     * the dialog appears once bytes arrive (or an error is shown). {@code onSaved} is invoked after a
     * successful save so the caller can refresh the pane.
     */
    public static void open(Window owner, FileSystem fs, FileItem item,
                            Consumer<String> logger, Runnable onSaved) {
        QuickView.Kind kind = QuickView.classify(item);
        if (kind == QuickView.Kind.UNSUPPORTED) {
            info(owner, item.name(), "No quick view for this file (unsupported type or larger than "
                    + FileItem.humanSize(QuickView.DEFAULT_MAX_BYTES) + ").");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Quick view — " + item.name());
        dialog.setHeaderText(null);
        if (owner != null) dialog.initOwner(owner);
        register(dialog.getDialogPane());

        Label status = new Label("Loading " + item.name() + "…");
        status.getStyleClass().add("meta-label");
        BorderPane body = new BorderPane();
        body.setBottom(status);
        BorderPane.setMargin(status, new Insets(6, 0, 0, 0));
        body.setPrefSize(720, 520);
        dialog.getDialogPane().setContent(body);

        boolean editable = kind == QuickView.Kind.TEXT && fs.supportsContentAccess();
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        if (editable) dialog.getDialogPane().getButtonTypes().add(saveType);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Button saveBtn = editable ? (Button) dialog.getDialogPane().lookupButton(saveType) : null;
        if (saveBtn != null) saveBtn.setDisable(true);

        TextArea editor = new TextArea();
        editor.setStyle("-fx-font-family: 'Monospaced';");
        editor.setEditable(kind == QuickView.Kind.TEXT);

        // Load the content in the background.
        Task<byte[]> load = new Task<>() {
            @Override protected byte[] call() throws Exception {
                return fs.readFile(item, QuickView.DEFAULT_MAX_BYTES);
            }
        };
        load.setOnSucceeded(e -> {
            byte[] data = load.getValue();
            if (kind == QuickView.Kind.IMAGE) {
                ImageView view = new ImageView(new Image(new ByteArrayInputStream(data)));
                view.setPreserveRatio(true);
                view.setFitWidth(700);
                ScrollPane sp = new ScrollPane(view);
                sp.setFitToWidth(true);
                body.setCenter(sp);
                status.setText(item.name() + " · " + FileItem.humanSize(data.length));
            } else {
                editor.setText(new String(data, StandardCharsets.UTF_8));
                VBox.setVgrow(editor, Priority.ALWAYS);
                body.setCenter(editor);
                status.setText(item.name() + " · " + FileItem.humanSize(data.length)
                        + (editable ? " · editable" : " · read-only"));
                if (saveBtn != null) saveBtn.setDisable(false);
            }
        });
        load.setOnFailed(e -> status.setText("✖ Load failed: " + msg(load.getException())));
        run(load, "quickview-load");

        if (saveBtn != null) {
            // Intercept the Save button so the dialog stays open and we can report the write result.
            saveBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
                ev.consume();
                String dir = fs.parent(item.path());
                byte[] out = editor.getText().getBytes(StandardCharsets.UTF_8);
                saveBtn.setDisable(true);
                status.setText("Saving…");
                Task<Void> save = new Task<>() {
                    @Override protected Void call() throws Exception {
                        fs.writeFile(dir, item.name(), out);
                        return null;
                    }
                };
                save.setOnSucceeded(e2 -> {
                    status.setText("Saved · " + FileItem.humanSize(out.length));
                    saveBtn.setDisable(false);
                    if (logger != null) logger.accept(fs.name() + ": saved " + item.path());
                    if (onSaved != null) onSaved.run();
                });
                save.setOnFailed(e2 -> { status.setText("✖ Save failed: " + msg(save.getException())); saveBtn.setDisable(false); });
                run(save, "quickview-save");
            });
        }

        dialog.showAndWait();
    }

    private static void run(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    private static String msg(Throwable t) { return t == null ? "unknown" : t.getMessage(); }

    private static void info(Window owner, String header, String content) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, content, ButtonType.OK);
        a.setHeaderText(header);
        if (owner != null) a.initOwner(owner);
        register(a.getDialogPane());
        a.showAndWait();
    }

    private static void register(DialogPane pane) {
        pane.sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
    }
}
