package com.nexuslink.ui.ftp;

import com.nexuslink.protocol.ftp.FtpExplorer;
import com.nexuslink.protocol.ftp.FtpService;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * FTP / FTPS client tab — connect with host/port + username/password (anonymous supported) and
 * browse the remote filesystem as a lazily-loaded directory tree.
 */
public final class FtpView extends BorderPane {

    private final FtpService service = new FtpService();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Remote files");

    private final TextField hostField = new TextField("test.rebex.net");
    private final TextField portField = new TextField("21");
    private final TextField userField = new TextField("demo");
    private final PasswordField passField = new PasswordField();
    private final CheckBox passiveBox = new CheckBox("Passive");
    private final CheckBox tlsBox = new CheckBox("FTPS");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private Consumer<String> logger = s -> {};

    public FtpView() {
        getStyleClass().add("ftp-view");
        passiveBox.setSelected(true);
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
            String t = target.replaceFirst("^ftp[s]?://", "");
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
        userField.setPrefWidth(130);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(130);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        HBox row = new HBox(8, lbl("Host:"), hostField, lbl("Port:"), portField,
                lbl("User:"), userField, passField, passiveBox, tlsBox, connectBtn, helpBtn);
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
        // Resolve ${VAR} against the active environment for host + credentials.
        String host = Env.resolve(hostField.getText().trim());
        String user = Env.resolve(userField.getText().trim());
        String pass = Env.resolve(passField.getText());
        logger.accept("FTP connect → " + user + "@" + host + ":" + port);

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                service.connect(host, port, user, pass, passiveBox.isSelected(), tlsBox.isSelected());
                return service.list("/").size();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " item(s) in /");
            logger.accept("FTP connected");
            explorer.setExplorer(new FtpExplorer(service, "/"));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("FTP connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        Thread t = new Thread(task, "ftp-task");
        t.setDaemon(true);
        t.start();
    }

    private int parsePort() {
        try { return Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { return 21; }
    }
}
