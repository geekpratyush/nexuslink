package com.nexuslink.ui.sql;

import com.nexuslink.protocol.db.DriverInfo;
import com.nexuslink.protocol.db.ExternalDriverLoader;
import com.nexuslink.protocol.db.JdbcDriverRegistry;
import com.nexuslink.protocol.db.JdbcService;
import com.nexuslink.protocol.db.QueryResult;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * JDBC SQL client tab — connect to any JDBC database, run SQL, browse results in a grid,
 * and inspect the schema. SQLite works out of the box (e.g. {@code jdbc:sqlite::memory:}).
 */
public final class SqlClientView extends BorderPane {

    private final JdbcService service = new JdbcService();

    private final ComboBox<DriverInfo> dbCombo = new ComboBox<>();
    private final Button driverBtn = new Button("Load Driver…");
    private final TextField urlField = new TextField("jdbc:sqlite::memory:");
    private final TextField userField = new TextField();
    private final PasswordField passField = new PasswordField();
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final ListView<String> tableList = new ListView<>();
    private final TextArea sqlEditor = new TextArea();
    private final TableView<List<String>> resultGrid = new TableView<>();
    private final Label resultStatus = new Label();

    private Consumer<String> logger = s -> {};

    public SqlClientView() {
        getStyleClass().add("sql-view");
        setTop(buildConnectionBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Demo/screenshot helper: connect to in-memory SQLite, seed data, and show a query. */
    public void runDemo() {
        Task<QueryResult> task = new Task<>() {
            @Override protected QueryResult call() throws Exception {
                service.connect("jdbc:sqlite::memory:", null, null);
                service.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, role TEXT)");
                service.execute("INSERT INTO users(name, role) VALUES "
                        + "('Alice','admin'),('Bob','developer'),('Carol','read-only'),('Dave','developer')");
                return service.execute("SELECT id, name, role FROM users ORDER BY id");
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected: in-memory SQLite");
            sqlEditor.setText("SELECT id, name, role FROM users ORDER BY id;");
            renderResult(task.getValue());
            refreshTables();
        });
        Thread t = new Thread(task, "sql-demo");
        t.setDaemon(true);
        t.start();
    }

    private VBox buildConnectionBar() {
        urlField.getStyleClass().add("nl-field");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        userField.getStyleClass().add("nl-field");
        userField.setPromptText("user (optional)");
        userField.setPrefWidth(120);
        passField.getStyleClass().add("nl-field");
        passField.setPromptText("password");
        passField.setPrefWidth(120);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        // Database picker — fills the URL template; flags on-demand drivers that need loading.
        dbCombo.getItems().setAll(JdbcDriverRegistry.all());
        dbCombo.setButtonCell(driverCell());
        dbCombo.setCellFactory(lv -> driverCell());
        dbCombo.valueProperty().addListener((o, ov, d) -> onDriverSelected(d));
        dbCombo.getSelectionModel().select(JdbcDriverRegistry.byId("sqlite").orElseThrow());

        driverBtn.getStyleClass().add("btn-secondary");
        driverBtn.setOnAction(e -> showDriverMenu());
        driverBtn.setVisible(false);
        driverBtn.setManaged(false);

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label lbl = new Label("Database:");
        lbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, lbl, dbCombo, driverBtn, urlField, userField, passField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private ListCell<DriverInfo> driverCell() {
        return new ListCell<>() {
            @Override protected void updateItem(DriverInfo d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) { setText(null); return; }
                // ⬇ marks on-demand drivers; ✓ marks already-loaded
                String mark = d.bundled() ? "" : (JdbcDriverRegistry.isAvailable(d) ? "  ✓" : "  ⬇");
                setText(d.displayName() + mark);
            }
        };
    }

    private void onDriverSelected(DriverInfo d) {
        if (d == null) return;
        urlField.setText(d.sampleUrl());
        boolean needsDriver = !d.bundled() && !JdbcDriverRegistry.isAvailable(d);
        driverBtn.setVisible(needsDriver);
        driverBtn.setManaged(needsDriver);
        if (needsDriver) {
            statusLabel.getStyleClass().setAll("status-4xx");
            statusLabel.setText("Driver for " + d.displayName() + " is not loaded — click \"Load Driver…\""
                    + (d.requiresLicenseAck() ? "  (licensed — accept terms before download)" : ""));
        } else {
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("Not connected");
        }
    }

    private void showDriverMenu() {
        DriverInfo d = dbCombo.getValue();
        if (d == null) return;
        ContextMenu menu = new ContextMenu();
        MenuItem browse = new MenuItem("Browse for driver JAR…");
        browse.setOnAction(e -> browseForDriver(d));
        MenuItem download = new MenuItem("Download from Maven Central  (" + d.mavenCoords() + ")");
        download.setOnAction(e -> downloadDriver(d));
        menu.getItems().addAll(browse, download);
        menu.show(driverBtn, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void browseForDriver(DriverInfo d) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select " + d.displayName() + " JDBC driver JAR");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR files", "*.jar"));
        var file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        runDriverLoad(() -> ExternalDriverLoader.loadFromJar(file.toPath(), d.driverClass()), d);
    }

    private void downloadDriver(DriverInfo d) {
        statusLabel.setText("Downloading " + d.mavenCoords() + "…");
        runDriverLoad(() -> { ExternalDriverLoader.downloadAndLoad(d.mavenCoords(), d.driverClass()); return true; }, d);
    }

    private void runDriverLoad(java.util.concurrent.Callable<Boolean> action, DriverInfo d) {
        logger.accept("Loading driver: " + d.displayName());
        Task<Boolean> task = new Task<>() {
            @Override protected Boolean call() throws Exception { return action.call(); }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText(d.displayName() + " driver loaded — ready to connect");
            logger.accept(d.displayName() + " driver loaded");
            driverBtn.setVisible(false);
            driverBtn.setManaged(false);
            dbCombo.setButtonCell(driverCell()); // refresh ✓ mark
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Driver load failed: " + task.getException().getMessage());
            logger.accept("Driver load FAILED: " + task.getException().getMessage());
        });
        runBg(task);
    }

    private SplitPane buildBody() {
        // Left: schema browser
        tableList.setOnMouseClicked(e -> {
            String sel = tableList.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && sel != null) {
                String table = sel.split("\\s")[0];
                sqlEditor.setText("SELECT * FROM " + table + " LIMIT 100;");
                runQuery();
            }
        });
        Label schemaTitle = new Label("TABLES");
        schemaTitle.getStyleClass().add("sidebar-title");
        VBox schema = new VBox(schemaTitle, tableList);
        VBox.setVgrow(tableList, Priority.ALWAYS);
        schema.setMinWidth(160);

        // Right: editor over results
        sqlEditor.getStyleClass().add("code-area");
        sqlEditor.setPromptText("SELECT * FROM ...   (Ctrl+Enter to run)");
        sqlEditor.setPrefRowCount(5);
        sqlEditor.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) runQuery();
        });

        Button runBtn = new Button("Run  (Ctrl+Enter)");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> runQuery());
        resultStatus.getStyleClass().add("meta-label");
        HBox runRow = new HBox(10, runBtn, resultStatus);
        runRow.setAlignment(Pos.CENTER_LEFT);
        runRow.setPadding(new Insets(6, 0, 6, 0));

        resultGrid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        resultGrid.setPlaceholder(new Label("Run a query to see results"));
        VBox.setVgrow(resultGrid, Priority.ALWAYS);

        VBox right = new VBox(6, sqlEditor, runRow, resultGrid);
        right.setPadding(new Insets(8));

        SplitPane sp = new SplitPane(schema, right);
        sp.setDividerPositions(0.22);
        return sp;
    }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.setText("Connecting…");
        String url = urlField.getText().trim();
        logger.accept("JDBC connect → " + url);
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                service.connect(url, userField.getText(), passField.getText());
                return service.databaseInfo();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected: " + task.getValue());
            logger.accept("JDBC connected: " + task.getValue());
            connectBtn.setDisable(false);
            refreshTables();
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("JDBC connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    private void refreshTables() {
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception { return service.listTables(); }
        };
        task.setOnSucceeded(e -> tableList.setItems(FXCollections.observableArrayList(task.getValue())));
        runBg(task);
    }

    private void runQuery() {
        String sql = sqlEditor.getText().trim();
        if (sql.isEmpty()) return;
        // Run only the statement at the caret-ish: take the first ;-separated non-empty statement
        String statement = sql.split(";")[0].trim();
        resultStatus.setText("Running…");
        logger.accept("SQL → " + truncate(statement));
        Task<QueryResult> task = new Task<>() {
            @Override protected QueryResult call() { return service.execute(statement); }
        };
        task.setOnSucceeded(e -> { renderResult(task.getValue()); refreshTables(); });
        task.setOnFailed(e -> resultStatus.setText("Error: " + task.getException().getMessage()));
        runBg(task);
    }

    private void renderResult(QueryResult r) {
        if (r.failed()) {
            resultStatus.getStyleClass().setAll("status-err");
            resultStatus.setText("✖ " + r.errorMessage());
            logger.accept("SQL FAILED — " + r.errorMessage());
            return;
        }
        resultStatus.getStyleClass().setAll("meta-label");
        resultStatus.setText(r.summary());
        logger.accept("SQL ok — " + r.summary());

        resultGrid.getColumns().clear();
        resultGrid.getItems().clear();
        if (!r.isResultSet()) return;

        for (int i = 0; i < r.columns().size(); i++) {
            final int col = i;
            TableColumn<List<String>, String> tc = new TableColumn<>(r.columns().get(i));
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().size() ? cd.getValue().get(col) : ""));
            resultGrid.getColumns().add(tc);
        }
        resultGrid.setItems(FXCollections.observableArrayList(r.rows()));
    }

    private String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private void runBg(Task<?> task) {
        Thread t = new Thread(task, "sql-task");
        t.setDaemon(true);
        t.start();
    }
}
