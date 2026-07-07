package com.nexuslink.ui.redis;

import com.nexuslink.protocol.redis.RedisCommandCatalog;
import com.nexuslink.protocol.redis.RedisExplorer;
import com.nexuslink.protocol.redis.RedisService;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.function.Consumer;

/**
 * Redis client tab — connect with a {@code redis://} URI, browse keys (with typed value preview in
 * the details panel), and run commands from a console.
 */
public final class RedisView extends BorderPane {

    private final RedisService service = new RedisService();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Keys");

    private final TextField uriField = new TextField("redis://localhost:6379");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final TextField commandField = new TextField();
    private final TextArea consoleOut = new TextArea();
    // Command-name auto-complete popup, driven by RedisCommandCatalog while typing the first token.
    private final ContextMenu completionPopup = new ContextMenu();

    private Consumer<String> logger = s -> {};

    public RedisView() {
        getStyleClass().add("redis-view");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Pre-fills the connection URI (used when opening a saved/sample connection). */
    public void prefill(String uri) {
        if (uri != null && !uri.isBlank()) uriField.setText(uri);
    }

    private VBox buildBar() {
        uriField.getStyleClass().add("nl-field");
        uriField.setPromptText("redis://[:password@]host:6379/0   (rediss:// for TLS)");
        HBox.setHgrow(uriField, Priority.ALWAYS);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label lbl = new Label("URI:");
        lbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, lbl, uriField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        explorer.setMinWidth(220);

        Label consoleTitle = new Label("COMMAND CONSOLE");
        consoleTitle.getStyleClass().add("sidebar-title");
        commandField.getStyleClass().add("nl-field");
        commandField.setPromptText("e.g.  SET greeting hello   |   GET greeting   |   KEYS *");
        commandField.setOnAction(e -> runCommand());
        completionPopup.setAutoHide(true);
        commandField.textProperty().addListener((o, old, text) -> updateCompletions(text));
        Button runBtn = new Button("Run");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> runCommand());
        HBox cmdRow = new HBox(8, commandField, runBtn);
        HBox.setHgrow(commandField, Priority.ALWAYS);
        cmdRow.setAlignment(Pos.CENTER_LEFT);

        consoleOut.getStyleClass().add("code-area");
        consoleOut.setEditable(false);
        consoleOut.setPromptText("Command output appears here…");

        VBox right = new VBox(6, consoleTitle, cmdRow, consoleOut);
        right.setPadding(new Insets(8));
        VBox.setVgrow(consoleOut, Priority.ALWAYS);

        SplitPane sp = new SplitPane(explorer, right);
        sp.setDividerPositions(0.34);
        return sp;
    }

    private void connect() {
        String uri = Env.resolve(uriField.getText().trim());   // resolve ${VAR} against active environment
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("Redis connect → " + uri.replaceAll(":[^:@/]+@", ":***@"));

        Task<Long> task = new Task<>() {
            @Override protected Long call() {
                service.connect(uri);
                return service.dbSize();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " key(s)");
            logger.accept("Redis connected — " + task.getValue() + " keys");
            explorer.setExplorer(new RedisExplorer(service));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("Redis connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    private void runCommand() {
        String cmd = Env.resolve(commandField.getText().trim());   // resolve ${VAR} in the command
        if (cmd.isEmpty() || !service.isConnected()) return;
        consoleOut.appendText("> " + cmd + "\n");
        Task<String> task = new Task<>() {
            @Override protected String call() { return service.execute(cmd); }
        };
        task.setOnSucceeded(e -> {
            consoleOut.appendText(task.getValue() + "\n\n");
            explorer.load(); // refresh keys (a write may have changed them)
        });
        task.setOnFailed(e -> consoleOut.appendText("ERR " + task.getException().getMessage() + "\n\n"));
        commandField.clear();
        runBg(task);
    }

    /**
     * Shows a command-name auto-complete popup while the user is typing the first token. Matches are
     * drawn from {@link RedisCommandCatalog}; picking one inserts the command name followed by a space.
     */
    private void updateCompletions(String text) {
        // Only complete the command name — once a space is typed the user is entering arguments.
        if (text == null || text.isBlank() || text.contains(" ")) {
            completionPopup.hide();
            return;
        }
        var matches = RedisCommandCatalog.complete(text.trim());
        if (matches.isEmpty()) {
            completionPopup.hide();
            return;
        }
        completionPopup.getItems().clear();
        matches.stream().limit(12).forEach(cmd -> {
            MenuItem item = new MenuItem(cmd.name() + "  —  " + cmd.summary());
            item.setOnAction(e -> {
                commandField.setText(cmd.name() + " ");
                commandField.positionCaret(commandField.getText().length());
                completionPopup.hide();
                commandField.requestFocus();
            });
            completionPopup.getItems().add(item);
        });
        if (!completionPopup.isShowing()) completionPopup.show(commandField, Side.BOTTOM, 0, 0);
    }

    private void runBg(Task<?> task) {
        Thread t = new Thread(task, "redis-task");
        t.setDaemon(true);
        t.start();
    }
}
