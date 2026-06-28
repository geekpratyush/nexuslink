package com.nexuslink.ui.files;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The WinSCP/MobaXterm-style two-pane file commander: the local disk on the left, a remote
 * service on the right, with Upload → / ← Download controls and a transfer progress bar in between.
 * Double-clicking a file in either pane transfers it to the other side's current directory.
 */
public final class DualPaneBrowser extends BorderPane {

    private final FileBrowserPane localPane;
    private final FileBrowserPane remotePane;
    private final FileTransfer transfer;

    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private Consumer<String> logger = s -> {};

    public DualPaneBrowser(FileSystem local, FileSystem remote, FileTransfer transfer) {
        this.transfer = transfer;
        this.localPane = new FileBrowserPane(local, "Local — " + local.name());
        this.remotePane = new FileBrowserPane(remote, "Remote — " + remote.name());
        getStyleClass().add("dual-pane-browser");

        // Double-click a file → transfer it to the opposite pane's directory.
        localPane.setOnActivateFile(f -> upload(List.of(f)));
        remotePane.setOnActivateFile(f -> download(List.of(f)));

        // Drag-and-drop: drop local files onto the remote pane to upload, and vice versa.
        remotePane.setOnDropFromOther(this::upload);
        localPane.setOnDropFromOther(this::download);

        SplitPane split = new SplitPane(localPane, buildTransferColumn(), remotePane);
        split.setDividerPositions(0.43, 0.57);
        setCenter(split);
        setBottom(buildProgressBar());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        localPane.setLogger(this.logger);
        remotePane.setLogger(this.logger);
    }

    /** Loads the local home in the left pane and the remote home in the right pane. */
    public void start() {
        localPane.loadHome();
        remotePane.loadHome();
    }

    private VBox buildTransferColumn() {
        Button upload = new Button("Upload →");
        upload.getStyleClass().add("btn-primary");
        upload.setTooltip(new javafx.scene.control.Tooltip("Send the selected local files to the remote directory"));
        upload.setOnAction(e -> upload(localPane.selected()));

        Button download = new Button("← Download");
        download.getStyleClass().add("btn-primary");
        download.setTooltip(new javafx.scene.control.Tooltip("Fetch the selected remote files to the local directory"));
        download.setOnAction(e -> download(remotePane.selected()));

        VBox col = new VBox(12, upload, download);
        col.setAlignment(Pos.CENTER);
        col.setPadding(new Insets(40, 6, 6, 6));
        col.setMinWidth(120);
        return col;
    }

    private HBox buildProgressBar() {
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progressLabel.getStyleClass().add("meta-label");
        progressLabel.setMinWidth(220);
        HBox bar = new HBox(10, progressLabel, progress);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        return bar;
    }

    private void upload(List<FileItem> items) {
        runTransfer(items, true);
    }

    private void download(List<FileItem> items) {
        runTransfer(items, false);
    }

    /** Transfers the given items; {@code uploading} chooses direction (local→remote vs remote→local). */
    private void runTransfer(List<FileItem> itemsRaw, boolean uploading) {
        List<FileItem> items = itemsRaw.stream().filter(f -> !f.parent() && !f.directory()).toList();
        if (items.isEmpty()) {
            progressLabel.setText("Select one or more files to transfer");
            return;
        }
        String remoteDir = remotePane.currentPath();
        Path localDir = Path.of(localPane.currentPath());

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                int i = 0;
                for (FileItem f : items) {
                    final int idx = ++i;
                    updateMessage((uploading ? "Uploading " : "Downloading ") + f.name()
                            + "  (" + idx + "/" + items.size() + ")");
                    long bytes = Math.max(f.size(), 1);
                    if (uploading) {
                        transfer.upload(Path.of(f.path()), remoteDir,
                                sent -> updateProgress(sent, bytes));
                    } else {
                        transfer.download(f, localDir,
                                read -> updateProgress(read, bytes));
                    }
                }
                return null;
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> finishTransfer(uploading, items.size(), null));
        task.setOnFailed(e -> finishTransfer(uploading, items.size(), task.getException()));
        Thread t = new Thread(task, "file-transfer");
        t.setDaemon(true);
        t.start();
    }

    private void finishTransfer(boolean uploading, int count, Throwable error) {
        progress.progressProperty().unbind();
        progressLabel.textProperty().unbind();
        if (error == null) {
            progress.setProgress(1);
            progressLabel.setText("✔ " + (uploading ? "Uploaded " : "Downloaded ") + count + " file(s)");
            logger.accept((uploading ? "Uploaded " : "Downloaded ") + count + " file(s)");
            Platform.runLater(() -> { if (uploading) remotePane.refresh(); else localPane.refresh(); });
        } else {
            progress.setProgress(0);
            progressLabel.setText("✖ Transfer failed: " + error.getMessage());
            logger.accept("Transfer FAILED: " + error.getMessage());
        }
    }
}
