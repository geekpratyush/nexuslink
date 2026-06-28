package com.nexuslink.ui.sftp;

import com.nexuslink.protocol.sftp.SftpService;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.files.DualPaneBrowser;
import com.nexuslink.ui.files.LocalFileSystem;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * SFTP client tab — connect over SSH (host/port + username/password or a private key) and manage
 * files through a WinSCP/MobaXterm-style two-pane commander: the local disk on the left, the remote
 * server on the right, with upload/download, drag-free transfers, new-folder, rename, delete and
 * chmod. Until connected, a placeholder explains the layout.
 */
public final class SftpView extends BorderPane {

    private final SftpService service = new SftpService();

    private final TextField hostField = new TextField("test.rebex.net");
    private final TextField portField = new TextField("22");
    private final TextField userField = new TextField("demo");
    private final PasswordField passField = new PasswordField();
    private final TextField keyField = new TextField();
    private final Button connectBtn = new Button("Connect");
    private final Button disconnectBtn = new Button("Disconnect");
    private final Label statusLabel = new Label("Not connected");
    private final StackPane bodyHost = new StackPane(placeholder());

    private DualPaneBrowser browser;
    private Consumer<String> logger = s -> {};

    public SftpView() {
        getStyleClass().add("sftp-view");
        setTop(buildBar());
        setCenter(bodyHost);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
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

    private Label placeholder() {
        Label l = new Label("Connect to an SFTP server to browse files.\n\n"
                + "Once connected you get a two-pane file manager:\n"
                + "   • Local files on the left, remote files on the right\n"
                + "   • Upload → / ← Download, double-click a file to transfer\n"
                + "   • New Folder, Rename (F2), Delete (Del), and chmod on the remote side");
        l.getStyleClass().add("meta-label");
        l.setAlignment(Pos.CENTER);
        return l;
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
        keyField.getStyleClass().add("nl-field");
        keyField.setPromptText("private key file (optional — overrides password)");
        HBox.setHgrow(keyField, Priority.ALWAYS);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());
        disconnectBtn.getStyleClass().add("btn-secondary");
        disconnectBtn.setDisable(true);
        disconnectBtn.setOnAction(e -> disconnect());

        Button keyBrowse = new Button("Key…");
        keyBrowse.getStyleClass().add("btn-secondary");
        keyBrowse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Select SSH private key");
            var f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) keyField.setText(f.getAbsolutePath());
        });

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        HBox row = new HBox(8, lbl("Host:"), hostField, lbl("Port:"), portField, lbl("User:"), userField,
                passField, connectBtn, disconnectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 4, 10));

        HBox keyRow = new HBox(8, lbl("Key:"), keyField, keyBrowse);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        keyRow.setPadding(new Insets(0, 10, 4, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, keyRow, statusRow);
    }

    private Label lbl(String t) { Label l = new Label(t); l.getStyleClass().add("meta-label"); return l; }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        int port = parsePort();
        String host = Env.resolve(hostField.getText().trim());
        String user = Env.resolve(userField.getText().trim());
        String pass = Env.resolve(passField.getText());
        String key = Env.resolve(keyField.getText().trim());
        logger.accept("SFTP connect → " + user + "@" + host + ":" + port);

        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                if (!key.isBlank()) service.connectWithKey(host, port, user, java.nio.file.Path.of(key));
                else service.connect(host, port, user, pass);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + user + "@" + host);
            logger.accept("SFTP connected");
            showBrowser();
            connectBtn.setDisable(false);
            disconnectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("SFTP connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        Thread t = new Thread(task, "sftp-connect");
        t.setDaemon(true);
        t.start();
    }

    private void showBrowser() {
        SftpFileSystem remote = new SftpFileSystem(service);
        browser = new DualPaneBrowser(new LocalFileSystem(), remote, remote);
        browser.setLogger(logger);
        bodyHost.getChildren().setAll(browser);
        browser.start();
    }

    private void disconnect() {
        service.close();
        browser = null;
        bodyHost.getChildren().setAll(placeholder());
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Not connected");
        disconnectBtn.setDisable(true);
        logger.accept("SFTP disconnected");
    }

    private int parsePort() {
        try { return Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { return 22; }
    }
}
