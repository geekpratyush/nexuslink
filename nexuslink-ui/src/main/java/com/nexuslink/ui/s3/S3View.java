package com.nexuslink.ui.s3;

import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.protocol.s3.S3Explorer;
import com.nexuslink.protocol.s3.S3PresignedUrl;
import com.nexuslink.protocol.s3.S3Service;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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

    private final ComboBox<PresignExpiry> presignExpiry = new ComboBox<>();
    private final Button presignBtn = new Button("Copy presigned link");
    // The currently selected object (bucket + key), or null when a bucket/nothing is selected.
    private String selectedBucket;
    private String selectedKey;

    // Connection parameters captured (already ${VAR}-resolved) at connect time so a presigned URL
    // is signed with exactly the credentials/endpoint the browser is connected with.
    private String connEndpoint;
    private String connAccessKey;
    private String connSecretKey;
    private String connRegion;
    private boolean connPathStyle;

    private Consumer<String> logger = s -> {};

    /** Preset lifetimes offered for a presigned URL. */
    private enum PresignExpiry {
        FIFTEEN_MIN("15 minutes", 900), ONE_HOUR("1 hour", 3600),
        ONE_DAY("24 hours", 86_400), SEVEN_DAYS("7 days", 604_800);
        final String label; final int seconds;
        PresignExpiry(String label, int seconds) { this.label = label; this.seconds = seconds; }
        @Override public String toString() { return label; }
    }

    public S3View() {
        getStyleClass().add("s3-view");
        presignExpiry.getItems().setAll(PresignExpiry.values());
        presignExpiry.getSelectionModel().select(PresignExpiry.ONE_HOUR);
        presignBtn.setDisable(true);
        presignBtn.setOnAction(e -> copyPresignedUrl());
        setTop(buildBar());
        explorer.setOnSelect(this::onNodeSelected);
        setCenter(explorer);
    }

    /** Remembers the selected object so a presigned link can be generated for it. */
    private void onNodeSelected(ResourceNode node) {
        if (node != null && node.kind() == ResourceNode.Kind.OBJECT && node.id().startsWith("obj:")) {
            String rest = node.id().substring("obj:".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                selectedBucket = rest.substring(0, slash);
                selectedKey = rest.substring(slash + 1);
                presignBtn.setDisable(false);
                return;
            }
        }
        selectedBucket = null;
        selectedKey = null;
        presignBtn.setDisable(true);
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
        presignBtn.getStyleClass().add("btn-secondary");
        presignBtn.setTooltip(new Tooltip(
                "Select an object, then copy a time-limited SigV4 presigned GET link to the clipboard."));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusRow = new HBox(8, statusLabel, spacer,
                label("Link expires:"), presignExpiry, presignBtn);
        statusRow.setAlignment(Pos.CENTER_LEFT);
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
            // Remember the resolved connection params for presigning.
            connEndpoint = endpoint;
            connAccessKey = accessKey;
            connSecretKey = secretKey;
            connRegion = region.isBlank() ? "us-east-1" : region;
            connPathStyle = pathStyle.isSelected();
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

    /** Signs a time-limited GET URL for the selected object and copies it to the clipboard. */
    private void copyPresignedUrl() {
        if (selectedBucket == null || selectedKey == null || connAccessKey == null) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Select an object first");
            return;
        }
        PresignExpiry expiry = presignExpiry.getValue();
        if (expiry == null) expiry = PresignExpiry.ONE_HOUR;
        try {
            String url = S3PresignedUrl.presign(S3PresignedUrl.Request.builder()
                    .accessKey(connAccessKey).secretKey(connSecretKey).region(connRegion)
                    .bucket(selectedBucket).objectKey(selectedKey)
                    .method("GET").expirySeconds(expiry.seconds)
                    .pathStyle(connPathStyle).endpoint(connEndpoint)
                    .build());
            ClipboardContent content = new ClipboardContent();
            content.putString(url);
            Clipboard.getSystemClipboard().setContent(content);
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Presigned link copied (valid " + expiry.label + ")");
            logger.accept("S3 presigned GET " + selectedBucket + "/" + selectedKey
                    + " (" + expiry.label + ") copied to clipboard");
        } catch (RuntimeException ex) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Presign failed: " + ex.getMessage());
            logger.accept("S3 presign FAILED: " + ex.getMessage());
        }
    }
}
