package com.nexuslink.ui.gcs;

import com.nexuslink.protocol.gcs.GcsExplorer;
import com.nexuslink.protocol.gcs.GcsService;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.util.function.Consumer;

/**
 * Google Cloud Storage client tab — connect with a project ID and an optional service-account JSON
 * key (or Application Default Credentials), then browse the bucket → object tree.
 */
public final class GcsView extends BorderPane {

    private final GcsService service = new GcsService();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Buckets");

    private final TextField projectField = new TextField();
    private final TextField credPathField = new TextField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private Consumer<String> logger = s -> {};

    public GcsView() {
        getStyleClass().add("gcs-view");
        setTop(buildBar());
        setCenter(explorer);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Pre-fills the project ID (used when opening a saved/sample connection). */
    public void prefill(String projectId) {
        if (projectId != null && !projectId.isBlank()) projectField.setText(projectId);
    }

    private VBox buildBar() {
        projectField.getStyleClass().add("nl-field");
        projectField.setPromptText("GCP project id");
        projectField.setPrefWidth(220);
        credPathField.getStyleClass().add("nl-field");
        credPathField.setPromptText("service-account JSON key path (optional — else uses ADC)");
        HBox.setHgrow(credPathField, Priority.ALWAYS);

        Button browse = new Button("Browse…");
        browse.getStyleClass().add("btn-secondary");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select service-account JSON key");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
            var file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (file != null) credPathField.setText(file.getAbsolutePath());
        });

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        HBox row1 = new HBox(8, label("Project:"), projectField, connectBtn, helpBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Key file:"), credPathField, browse);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, statusRow);
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void connect() {
        String project = projectField.getText().trim();
        String credPath = credPathField.getText().trim();
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("GCS connect → project " + project);

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                service.connect(project, credPath);
                return service.listBuckets().size();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " bucket(s)");
            logger.accept("GCS connected — " + task.getValue() + " buckets");
            explorer.setExplorer(new GcsExplorer(service));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("GCS connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        Thread t = new Thread(task, "gcs-task");
        t.setDaemon(true);
        t.start();
    }
}
