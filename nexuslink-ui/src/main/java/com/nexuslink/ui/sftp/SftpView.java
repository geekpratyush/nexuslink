package com.nexuslink.ui.sftp;

import com.nexuslink.protocol.sftp.SftpExplorer;
import com.nexuslink.protocol.sftp.SftpService;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * SFTP client tab — connect over SSH (host/port + username/password) and browse the remote
 * filesystem as a lazily-loaded directory tree with per-file details.
 */
public final class SftpView extends BorderPane {

    private final SftpService service = new SftpService();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Remote files");

    private final TextField hostField = new TextField("test.rebex.net");
    private final TextField portField = new TextField("22");
    private final TextField userField = new TextField("demo");
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private Consumer<String> logger = s -> {};

    public SftpView() {
        getStyleClass().add("sftp-view");
        setTop(buildBar());
        setCenter(explorer);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Pre-fills connection details (used when opening a saved/sample connection). */
    public void prefill(String target, String user, String password) {
        if (target != null && !target.isBlank()) {
            String t = target.replaceFirst("^sftp://", "");
            int colon = t.lastIndexOf(':');
            if (colon > 0) { hostField.setText(t.substring(0, colon)); portField.setText(t.substring(colon + 1)); }
            else hostField.setText(t);
        }
        if (user != null) userField.setText(user);
        if (password != null) passField.setText(password);
    }

    private VBox buildBar() {
        hostField.getStyleClass().add("nl-field");
        HBox.setHgrow(hostField, Priority.ALWAYS);
        portField.getStyleClass().add("nl-field");
        portField.setPrefWidth(80);
        userField.getStyleClass().add("nl-field");
        userField.setPrefWidth(140);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(140);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label h = lbl("Host:"); Label p = lbl("Port:"); Label u = lbl("User:");
        HBox row = new HBox(8, h, hostField, p, portField, u, userField, passField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private Label lbl(String t) { Label l = new Label(t); l.getStyleClass().add("meta-label"); return l; }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        int port = parsePort();
        logger.accept("SFTP connect → " + userField.getText() + "@" + hostField.getText() + ":" + port);

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                service.connect(hostField.getText().trim(), port, userField.getText().trim(), passField.getText());
                return service.list("/").size();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " item(s) in /");
            logger.accept("SFTP connected");
            explorer.setExplorer(new SftpExplorer(service, "/"));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("SFTP connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        Thread t = new Thread(task, "sftp-task");
        t.setDaemon(true);
        t.start();
    }

    private int parsePort() {
        try { return Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { return 22; }
    }
}
