package com.nexuslink.ui.azure;

import com.nexuslink.protocol.azure.AzureBlobExplorer;
import com.nexuslink.protocol.azure.AzureBlobService;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * Azure Blob Storage client tab — connect with a storage connection string (incl. the local Azurite
 * emulator) and browse the container → blob tree with per-blob details.
 */
public final class AzureBlobView extends BorderPane {

    private final AzureBlobService service = new AzureBlobService();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Containers");

    private final TextField connStringField = new TextField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private Consumer<String> logger = s -> {};

    public AzureBlobView() {
        getStyleClass().add("azure-view");
        setTop(buildBar());
        setCenter(explorer);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Pre-fills the connection string (used when opening a saved/sample connection). */
    public void prefill(String connectionString) {
        if (connectionString != null && !connectionString.isBlank()) connStringField.setText(connectionString);
    }

    private VBox buildBar() {
        connStringField.getStyleClass().add("nl-field");
        connStringField.setPromptText("DefaultEndpointsProtocol=https;AccountName=…;AccountKey=…;EndpointSuffix=core.windows.net");
        HBox.setHgrow(connStringField, Priority.ALWAYS);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label lbl = new Label("Connection string:");
        lbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, lbl, connStringField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private void connect() {
        String conn = connStringField.getText().trim();
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("Azure Blob connect");

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() {
                service.connect(conn);
                return service.listContainers().size();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " container(s)");
            logger.accept("Azure Blob connected — " + task.getValue() + " containers");
            explorer.setExplorer(new AzureBlobExplorer(service));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("Azure Blob connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        Thread t = new Thread(task, "azure-task");
        t.setDaemon(true);
        t.start();
    }
}
