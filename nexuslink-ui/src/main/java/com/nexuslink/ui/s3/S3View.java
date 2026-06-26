package com.nexuslink.ui.s3;

import com.nexuslink.protocol.s3.S3Explorer;
import com.nexuslink.protocol.s3.S3Service;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * S3 / object-storage client tab — connect to any S3-compatible endpoint (AWS, MinIO, Wasabi, …)
 * and browse the bucket → object tree with per-object details. Path-style access is on by default
 * so non-AWS endpoints (e.g. MinIO Play) work out of the box.
 */
public final class S3View extends BorderPane {

    private final S3Service service = new S3Service();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Buckets");

    private final TextField endpointField = new TextField("https://play.min.io");
    private final TextField accessKeyField = new TextField();
    private final PasswordField secretKeyField = new PasswordField();
    private final TextField regionField = new TextField("us-east-1");
    private final CheckBox pathStyle = new CheckBox("Path-style");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private Consumer<String> logger = s -> {};

    public S3View() {
        getStyleClass().add("s3-view");
        setTop(buildBar());
        setCenter(explorer);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Pre-fills connection details (used when opening a saved/sample connection). */
    public void prefill(String endpoint, String accessKey, String secretKey) {
        if (endpoint != null && !endpoint.isBlank()) endpointField.setText(endpoint);
        if (accessKey != null) accessKeyField.setText(accessKey);
        if (secretKey != null) secretKeyField.setText(secretKey);
    }

    private VBox buildBar() {
        endpointField.getStyleClass().add("nl-field");
        endpointField.setPromptText("https://s3.amazonaws.com  or  https://play.min.io");
        HBox.setHgrow(endpointField, Priority.ALWAYS);
        accessKeyField.getStyleClass().add("nl-field");
        accessKeyField.setPromptText("access key");
        accessKeyField.setPrefWidth(180);
        secretKeyField.getStyleClass().add("nl-field");
        secretKeyField.setPromptText("secret key");
        secretKeyField.setPrefWidth(180);
        regionField.getStyleClass().add("nl-field");
        regionField.setPrefWidth(110);
        pathStyle.setSelected(true);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        HBox row1 = new HBox(8, label("Endpoint:"), endpointField, connectBtn, helpBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Access:"), accessKeyField, label("Secret:"), secretKeyField,
                label("Region:"), regionField, pathStyle);
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
        // Resolve ${VAR} against the active environment for endpoint + credentials + region.
        String endpoint = Env.resolve(endpointField.getText().trim());
        String accessKey = Env.resolve(accessKeyField.getText().trim());
        String secretKey = Env.resolve(secretKeyField.getText());
        String region = Env.resolve(regionField.getText().trim());
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("S3 connect → " + endpoint);

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                service.connect(endpoint, accessKey, secretKey, region, pathStyle.isSelected());
                return service.listBuckets().size();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " bucket(s)");
            logger.accept("S3 connected — " + task.getValue() + " buckets");
            explorer.setExplorer(new S3Explorer(service));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("S3 connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        Thread t = new Thread(task, "s3-task");
        t.setDaemon(true);
        t.start();
    }
}
