package com.nexuslink.ui.sql;

import com.nexuslink.core.connection.AuthMethod;
import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.protocol.db.DriverInfo;
import com.nexuslink.protocol.db.ExternalDriverLoader;
import com.nexuslink.protocol.db.JdbcDriverRegistry;
import com.nexuslink.protocol.db.JdbcExplorer;
import com.nexuslink.protocol.db.JdbcService;
import com.nexuslink.protocol.db.JdbcTlsParams;
import com.nexuslink.protocol.db.JdbcTlsSpec;
import com.nexuslink.protocol.db.QueryResult;
import com.nexuslink.protocol.db.ResultGridExporter;
import com.nexuslink.protocol.db.SslMode;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    // TLS / SSL material (driver-specific params are derived from these at connect time).
    private final TitledPane tlsPane = new TitledPane();
    private final ComboBox<SslMode> sslModeCombo = new ComboBox<>();
    private final TextField caField = new TextField();
    private final PasswordField caPwField = new PasswordField();
    private final TextField clientCertField = new TextField();
    private final TextField clientKeyField = new TextField();
    private final PasswordField clientPwField = new PasswordField();
    private final CheckBox trustAllCheck = new CheckBox("Trust any server certificate (no verification — testing only)");

    private final ResourceExplorerView explorer = new ResourceExplorerView("Schema");
    private final CodeArea sqlEditor = SqlHighlighter.area();
    private final TableView<List<String>> resultGrid = new TableView<>();
    private final TextArea messagesArea = new TextArea();
    private final TabPane resultTabs = new TabPane();
    private final Label resultStatus = new Label();
    // Stats strip (rows · cols · ms), each a value label styled via .stat-chip .value.
    private final Label statRows = new Label("0");
    private final Label statCols = new Label("0");
    private final Label statMs = new Label("0");

    // Autocomplete: SQL vocabulary plus schema words (tables + columns) cached on connect/refresh.
    private final java.util.List<String> schemaWords = new java.util.ArrayList<>();
    private final ContextMenu completionPopup = new ContextMenu();

    // Editability context for the displayed grid: the single source table + its PK columns, resolved
    // from the executed statement. Empty when the result isn't a simple single-table SELECT — which
    // keeps row deletes (and future in-grid UPDATEs) safe and unambiguous.
    private String editTable;
    private final java.util.List<String> editPk = new java.util.ArrayList<>();
    private final java.util.List<String> currentColumns = new java.util.ArrayList<>();

    // Result row model: master → filter (live text search) → sort (column headers).
    private final ObservableList<List<String>> masterRows = FXCollections.observableArrayList();
    private final FilteredList<List<String>> filteredRows = new FilteredList<>(masterRows, r -> true);
    private final SortedList<List<String>> sortedRows = new SortedList<>(filteredRows);
    private final TextField filterField = new TextField();

    private Consumer<String> logger = s -> {};
    private Consumer<ConnectionProfile> onSave = p -> {};
    private Consumer<com.nexuslink.core.history.HistoryEntry> historyRecorder = e -> {};

    public SqlClientView() {
        getStyleClass().add("sql-view");
        setTop(buildConnectionBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Notified when the user saves the current connection as a profile. */
    public void setOnSave(Consumer<ConnectionProfile> onSave) {
        this.onSave = onSave == null ? p -> {} : onSave;
    }

    /** Records executed queries into the shared history store; set by the main window. */
    public void setHistoryRecorder(Consumer<com.nexuslink.core.history.HistoryEntry> recorder) {
        this.historyRecorder = recorder == null ? e -> {} : recorder;
    }

    /** Replays a query from a history detail JSON: restores the URL + statement, ready to run. */
    public void loadQuery(String detailJson) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(detailJson);
            String url = node.path("url").asText("");
            String sql = node.path("sql").asText("");
            if (!url.isBlank()) urlField.setText(url);
            if (!sql.isBlank()) setEditorText(sql);
        } catch (Exception ignored) {
            // A malformed detail blob just leaves the editor as-is.
        }
    }

    /** Pre-fills connection fields (used when opening a saved/sample connection). */
    public void prefill(String url, String user, String password) {
        if (url != null && !url.isBlank()) urlField.setText(url);
        if (user != null) userField.setText(user);
        if (password != null) passField.setText(password);
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
            setEditorText("SELECT id, name, role FROM users ORDER BY id;");
            renderResult(task.getValue());
            refreshExplorer();
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

        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> saveCurrent());

        Button erBtn = new Button("ER Diagram");
        erBtn.getStyleClass().add("btn-secondary");
        erBtn.setTooltip(new Tooltip("Generate an entity-relationship diagram of the schema"));
        erBtn.setOnAction(e -> showErDiagram());

        MenuButton structureBtn = new MenuButton("Structure");
        structureBtn.getStyleClass().add("btn-secondary");
        MenuItem createTable = new MenuItem("Create Table…");
        createTable.setOnAction(e -> createTableDialog());
        MenuItem createIndex = new MenuItem("Create Index…");
        createIndex.setOnAction(e -> createIndexDialog());
        MenuItem exportStructure = new MenuItem("Export Structure…");
        exportStructure.setOnAction(e -> exportStructureDialog());
        structureBtn.getItems().addAll(createTable, createIndex, new SeparatorMenuItem(), exportStructure);

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
        HBox row = new HBox(8, lbl, dbCombo, driverBtn, urlField, userField, passField, connectBtn, saveBtn, erBtn, structureBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, buildTlsPane(), statusRow);
    }

    /**
     * Collapsible TLS / SSL section. The fields are database-agnostic; at connect time
     * {@link JdbcTlsParams} translates them into the selected driver's own parameters
     * (PostgreSQL {@code sslmode}/{@code sslrootcert}, MySQL {@code sslMode}/keystore URLs, …).
     */
    private TitledPane buildTlsPane() {
        sslModeCombo.getItems().setAll(SslMode.values());
        sslModeCombo.getSelectionModel().select(SslMode.DEFAULT);
        sslModeCombo.setButtonCell(sslModeCell());
        sslModeCombo.setCellFactory(lv -> sslModeCell());

        for (TextField f : new TextField[]{caField, clientCertField, clientKeyField}) {
            f.getStyleClass().add("nl-field");
            HBox.setHgrow(f, Priority.ALWAYS);
        }
        for (PasswordField f : new PasswordField[]{caPwField, clientPwField}) {
            f.getStyleClass().add("nl-field");
            f.setPrefWidth(120);
            f.setPromptText("password");
        }
        caField.setPromptText("CA cert .pem  or  trust store .p12/.jks (verifies the server)");
        clientCertField.setPromptText("client cert .pem  or  client key store .p12/.jks (mutual TLS)");
        clientKeyField.setPromptText("client key .pem/.pk8  (only for a PEM client cert)");

        Label modeL = new Label("SSL mode:"); modeL.getStyleClass().add("meta-label");
        Label caL = new Label("Server CA / trust store:"); caL.getStyleClass().add("meta-label");
        Label ccL = new Label("Client cert / key store:"); ccL.getStyleClass().add("meta-label");
        Label ckL = new Label("Client key (PEM):"); ckL.getStyleClass().add("meta-label");

        HBox modeRow = new HBox(8, modeL, sslModeCombo);
        HBox caRow = new HBox(8, caL, caField, browseTls(caField), caPwField);
        HBox ccRow = new HBox(8, ccL, clientCertField, browseTls(clientCertField), clientPwField);
        HBox ckRow = new HBox(8, ckL, clientKeyField, browseTls(clientKeyField));
        for (HBox h : new HBox[]{modeRow, caRow, ccRow, ckRow}) h.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(6, modeRow, caRow, ccRow, ckRow, trustAllCheck);
        content.setPadding(new Insets(6, 10, 6, 10));
        tlsPane.setText("TLS / SSL  (for encrypted database connections — Postgres, MySQL, MariaDB, SQL Server)");
        tlsPane.setContent(content);
        tlsPane.setExpanded(false);
        return tlsPane;
    }

    private ListCell<SslMode> sslModeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(SslMode m, boolean empty) {
                super.updateItem(m, empty);
                setText(empty || m == null ? null : switch (m) {
                    case DEFAULT -> "Default (use URL settings)";
                    case DISABLE -> "Disable (no TLS)";
                    case REQUIRE -> "Require (encrypt, no verify)";
                    case VERIFY_CA -> "Verify CA";
                    case VERIFY_FULL -> "Verify Full (CA + hostname)";
                });
            }
        };
    }

    private Button browseTls(TextField target) {
        Button b = new Button("Browse…");
        b.getStyleClass().add("btn-secondary");
        b.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Choose certificate or key store");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Certs & key stores", "*.pem", "*.crt", "*.cer", "*.der", "*.key", "*.pk8", "*.p12", "*.pfx", "*.jks"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            var f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) target.setText(f.getAbsolutePath());
        });
        return b;
    }

    /** Builds a {@link JdbcTlsSpec} from the TLS fields, sorting PEM files from key stores by extension. */
    private JdbcTlsSpec tlsSpec() {
        SslMode mode = sslModeCombo.getValue() == null ? SslMode.DEFAULT : sslModeCombo.getValue();
        String ca = blankToNull(Env.resolve(caField.getText().trim()));
        String caPw = blankToNull(caPwField.getText());
        String clientCert = blankToNull(Env.resolve(clientCertField.getText().trim()));
        String clientKey = blankToNull(Env.resolve(clientKeyField.getText().trim()));
        String clientPw = blankToNull(clientPwField.getText());

        // CA field: a key store (.p12/.jks/.pfx) → trust store; otherwise a PEM CA cert.
        String caCert = null, trustStore = null, trustStorePw = null;
        if (ca != null) {
            if (isKeyStore(ca)) { trustStore = ca; trustStorePw = caPw; }
            else caCert = ca;
        }
        // Client field: a key store → client key store; otherwise a PEM cert (+ separate PEM key).
        String clientCertPem = null, clientKeyPem = null, keyStore = null, keyStorePw = null;
        if (clientCert != null) {
            if (isKeyStore(clientCert)) { keyStore = clientCert; keyStorePw = clientPw; }
            else { clientCertPem = clientCert; clientKeyPem = clientKey; }
        }
        return new JdbcTlsSpec(mode, trustAllCheck.isSelected(),
                caCert, clientCertPem, clientKeyPem,
                trustStore, trustStorePw, null,
                keyStore, keyStorePw, null);
    }

    private static boolean isKeyStore(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".p12") || p.endsWith(".pfx") || p.endsWith(".jks");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

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
        // Left: live schema explorer (database → tables/views → columns)
        explorer.setMinWidth(200);
        explorer.setOnActivate(node -> {
            if (node.kind() == ResourceNode.Kind.TABLE) {
                // Node ids are "table:NAME" or "view:NAME"; both are queryable with SELECT *.
                String id = node.id();
                String table = id.startsWith("view:") ? id.substring("view:".length())
                                                       : id.substring("table:".length());
                setEditorText("SELECT * FROM " + table + " LIMIT 100;");
                runQuery();
            }
        });
        explorer.setContextMenuFactory(this::explorerMenu);

        // Right: syntax-highlighted editor over tabbed results.
        VirtualizedScrollPane<CodeArea> editorScroll = new VirtualizedScrollPane<>(sqlEditor);
        editorScroll.setPrefHeight(150);
        VBox.setVgrow(editorScroll, Priority.NEVER);
        sqlEditor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) { runQuery(); e.consume(); }
            else if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.SPACE) { showCompletions(); e.consume(); }
            else if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.SLASH) { toggleLineComment(); e.consume(); }
        });

        Button runBtn = new Button("Run  ⌘/Ctrl+Enter");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setTooltip(new Tooltip("Run the selection, or all statements if nothing is selected"));
        runBtn.setOnAction(e -> runQuery());
        Button runSelBtn = new Button("Run selection");
        runSelBtn.getStyleClass().add("btn-secondary");
        runSelBtn.setOnAction(e -> runSelection());
        Button formatBtn = new Button("Format");
        formatBtn.getStyleClass().add("btn-secondary");
        formatBtn.setTooltip(new Tooltip("Tidy the SQL — upper-case keywords, break before clauses"));
        formatBtn.setOnAction(e -> formatEditor());
        Button explainBtn = new Button("Explain");
        explainBtn.getStyleClass().add("btn-secondary");
        explainBtn.setTooltip(new Tooltip("Show the query plan for the current statement"));
        explainBtn.setOnAction(e -> explainQuery());
        Button chartBtn = new Button("Chart");
        chartBtn.getStyleClass().add("btn-secondary");
        chartBtn.setTooltip(new Tooltip("Plot two columns of the current result as a bar chart"));
        chartBtn.setOnAction(e -> chartDialog());
        resultStatus.getStyleClass().add("meta-label");
        Region runSpacer = new Region();
        HBox.setHgrow(runSpacer, Priority.ALWAYS);
        HBox runRow = new HBox(8, runBtn, runSelBtn, formatBtn, explainBtn, chartBtn, runSpacer, statsStrip(), resultStatus);
        runRow.setAlignment(Pos.CENTER_LEFT);
        runRow.setPadding(new Insets(6, 0, 6, 0));

        // Content-sized columns (like DBeaver / SQL Developer): each column is as wide as its data
        // needs, with any leftover space trailing after the last column — far cleaner than a
        // constrained policy ballooning one column. Widths are computed per query in renderResult.
        resultGrid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        resultGrid.setPlaceholder(new Label("Run a query to see results"));

        // Right-click a result row → copy it, or delete it (via the preview-then-apply gate).
        resultGrid.setRowFactory(tv -> {
            TableRow<List<String>> row = new TableRow<>();
            MenuItem copy = new MenuItem("Copy row");
            copy.setOnAction(e -> { if (row.getItem() != null) copyToClipboard(String.join("\t", nullSafe(row.getItem()))); });
            MenuItem delete = new MenuItem("Delete row…");
            delete.setOnAction(e -> deleteRow(row.getItem()));
            ContextMenu menu = new ContextMenu(copy, delete);
            row.emptyProperty().addListener((o, was, empty) -> row.setContextMenu(empty ? null : menu));
            return row;
        });

        // Keep sorting and filtering composed: sorted(filtered(master)). The grid's own
        // sort order drives the comparator; the filter field narrows visible rows live.
        sortedRows.comparatorProperty().bind(resultGrid.comparatorProperty());
        resultGrid.setItems(sortedRows);
        filterField.getStyleClass().add("nl-field");
        filterField.setPromptText("Filter rows… (matches any cell, case-insensitive)");
        HBox.setHgrow(filterField, Priority.ALWAYS);
        filterField.textProperty().addListener((o, ov, text) -> applyRowFilter(text));

        Button exportJson = new Button("Export JSON…");
        exportJson.getStyleClass().add("btn-secondary");
        exportJson.setOnAction(e -> exportResults(true));
        Button exportCsv = new Button("Export CSV…");
        exportCsv.getStyleClass().add("btn-secondary");
        exportCsv.setOnAction(e -> exportResults(false));

        HBox gridTools = new HBox(8, filterField, exportJson, exportCsv);
        gridTools.setAlignment(Pos.CENTER_LEFT);
        gridTools.setPadding(new Insets(0, 0, 6, 0));

        VBox gridPane = new VBox(6, gridTools, resultGrid);
        VBox.setVgrow(resultGrid, Priority.ALWAYS);
        gridPane.setPadding(new Insets(6));

        messagesArea.setEditable(false);
        messagesArea.getStyleClass().add("code-area");
        messagesArea.setPromptText("Statement messages appear here");

        Tab resultTab = new Tab("Result", gridPane);
        resultTab.setClosable(false);
        Tab msgTab = new Tab("Messages", messagesArea);
        msgTab.setClosable(false);
        resultTabs.getTabs().setAll(resultTab, msgTab);
        resultTabs.getStyleClass().add("editor-tabs");
        VBox.setVgrow(resultTabs, Priority.ALWAYS);

        VBox right = new VBox(6, editorScroll, runRow, resultTabs);
        right.setPadding(new Insets(8));

        SplitPane sp = new SplitPane(explorer, right);
        sp.setDividerPositions(0.26);
        return sp;
    }

    /** rows · cols · ms chips shown next to the Run button. */
    private HBox statsStrip() {
        HBox strip = new HBox(14, chip("rows", statRows), chip("cols", statCols), chip("ms", statMs));
        strip.setAlignment(Pos.CENTER_LEFT);
        return strip;
    }

    private HBox chip(String name, Label value) {
        Label caption = new Label(name);
        value.getStyleClass().add("value");
        HBox box = new HBox(4, value, caption);
        box.getStyleClass().add("stat-chip");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void setStats(int rows, int cols, long ms) {
        statRows.setText(String.valueOf(rows));
        statCols.setText(String.valueOf(cols));
        statMs.setText(String.valueOf(ms));
    }

    private void showErDiagram() {
        if (!service.isConnected()) { statusLabel.setText("Connect to a database first"); return; }
        statusLabel.setText("Loading tables…");
        Task<List<String>> listTask = new Task<>() {
            @Override protected List<String> call() throws Exception {
                List<String> out = new ArrayList<>();
                for (String t : service.listTables()) {
                    if (!t.trim().endsWith("(view)")) out.add(t.trim());
                }
                return out;
            }
        };
        listTask.setOnSucceeded(e -> {
            List<String> tables = listTask.getValue();
            if (tables.isEmpty()) { statusLabel.setText("No tables to diagram"); return; }
            List<String> selected = pickTables(tables, "ER Diagram", "Select tables to include");
            if (selected == null) { statusLabel.setText("ER diagram cancelled"); return; }
            if (selected.isEmpty()) { statusLabel.setText("Pick at least one table"); return; }
            buildErDiagram(selected);
        });
        listTask.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Could not list tables: " + listTask.getException().getMessage());
        });
        runBg(listTask);
    }

    /**
     * Pick tables/views, then generate portable CREATE-TABLE DDL for them (via {@link
     * com.nexuslink.protocol.db.SchemaExporter}) — a structure dump to share with a teammate or hand
     * to a coding assistant. Shown in a dialog with Copy-to-clipboard and Save-to-file.
     */
    private void exportStructureDialog() {
        if (!service.isConnected()) { statusLabel.setText("Connect to a database first"); return; }
        statusLabel.setText("Loading objects…");
        Task<List<String>> listTask = new Task<>() {
            @Override protected List<String> call() throws Exception {
                List<String> out = new ArrayList<>();
                for (String t : service.listTables()) out.add(t.trim());   // keeps the "(view)" marker
                return out;
            }
        };
        listTask.setOnSucceeded(e -> {
            List<String> objects = listTask.getValue();
            if (objects.isEmpty()) { statusLabel.setText("No tables or views to export"); return; }
            List<String> selected = pickTables(objects, "Export Structure", "Select tables / views to export");
            if (selected == null) { statusLabel.setText("Structure export cancelled"); return; }
            if (selected.isEmpty()) { statusLabel.setText("Pick at least one object"); return; }
            // Strip the "  (view)" display marker before asking the exporter for them.
            List<String> names = new ArrayList<>();
            for (String s : selected) names.add(s.replace("  (view)", "").trim());
            generateStructure(names);
        });
        listTask.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Could not list objects: " + listTask.getException().getMessage());
        });
        runBg(listTask);
    }

    // ---- schema-tree right-click actions ----------------------------------------------------

    /** Builds the context menu for a schema-tree node (table/view/column/…). Null for none. */
    private ContextMenu explorerMenu(ResourceNode node) {
        if (node == null) return null;
        String id = node.id();
        if (id.startsWith("table:") || id.startsWith("view:")) {
            boolean view = id.startsWith("view:");
            String name = id.substring(id.indexOf(':') + 1);
            MenuItem select = new MenuItem("Generate SELECT");
            select.setOnAction(e -> { setEditorText("SELECT * FROM " + name + " LIMIT 100;"); runQuery(); });
            MenuItem ddl = new MenuItem("View / export DDL…");
            ddl.setOnAction(e -> generateStructure(List.of(name)));
            MenuItem copy = new MenuItem("Copy name");
            copy.setOnAction(e -> copyToClipboard(name));
            ContextMenu menu = new ContextMenu(select, ddl, copy);
            if (!view) {
                MenuItem addCol = new MenuItem("Add column…");
                addCol.setOnAction(e -> addColumnDialog(name));
                menu.getItems().add(addCol);
            }
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem drop = new MenuItem(view ? "Drop view…" : "Drop table…");
            drop.setOnAction(e -> previewAndApply("DROP " + (view ? "VIEW" : "TABLE") + " " + quoteIdent(name) + ";",
                    "Drop " + (view ? "view" : "table") + " “" + name + "”?"));
            menu.getItems().add(drop);
            return menu;
        }
        if (id.startsWith("col:")) {
            String rest = id.substring("col:".length());
            int dot = rest.lastIndexOf('.');   // "table.column" — column names carry no dot
            String table = dot > 0 ? rest.substring(0, dot) : rest;
            String col = node.details().getOrDefault("Column", dot > 0 ? rest.substring(dot + 1) : node.label());
            MenuItem copy = new MenuItem("Copy column name");
            copy.setOnAction(e -> copyToClipboard(col));
            MenuItem insert = new MenuItem("Insert into editor");
            insert.setOnAction(e -> sqlEditor.insertText(sqlEditor.getCaretPosition(), col));
            MenuItem rename = new MenuItem("Rename column…");
            rename.setOnAction(e -> renameColumnDialog(table, col));
            MenuItem drop = new MenuItem("Drop column…");
            drop.setOnAction(e -> previewAndApply(
                    "ALTER TABLE " + quoteIdent(table) + " DROP COLUMN " + quoteIdent(col) + ";",
                    "Drop column “" + col + "” from “" + table + "”?"));
            return new ContextMenu(copy, insert, new SeparatorMenuItem(), rename, drop);
        }
        return null;
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    private static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /**
     * Preview-then-apply confirm for any statement that <b>writes</b> to the database: shows the exact
     * SQL (read-only, highlighted) in a dialog; only on explicit Apply does it run — then the schema
     * tree refreshes and the outcome lands in the Messages tab. This is the safety gate for every
     * generated mutation (DROP now; UPDATE/DELETE/ALTER as they land).
     */
    private void previewAndApply(String sql, String header) { previewAndApply(sql, header, null); }

    private void previewAndApply(String sql, String header, Runnable onApplied) {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("Confirm change");
        d.setHeaderText(header + "\nReview the SQL below — it runs only when you click Apply.");
        ButtonType apply = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        CodeArea preview = SqlHighlighter.area();
        preview.replaceText(sql);
        preview.setEditable(false);
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(preview);
        scroll.setPrefSize(520, 140);
        d.getDialogPane().setContent(scroll);
        d.setOnShown(ev -> {
            if (d.getDialogPane().getScene() != null)
                com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene());
        });
        // Make the Apply button visually a "danger" action.
        javafx.application.Platform.runLater(() -> {
            var btn = d.getDialogPane().lookupButton(apply);
            if (btn != null) btn.getStyleClass().add("btn-primary");
        });
        if (d.showAndWait().orElse(ButtonType.CANCEL) != apply) { statusLabel.setText("Change cancelled"); return; }
        runWrite(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql, onApplied);
    }

    /** Runs a confirmed mutation off-thread; on success refreshes the schema tree, logs, and fires onApplied. */
    private void runWrite(String sql, Runnable onApplied) {
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Applying…");
        logger.accept("SQL write → " + truncate(sql));
        Task<QueryResult> task = new Task<>() {
            @Override protected QueryResult call() { return service.execute(sql); }
        };
        task.setOnSucceeded(e -> {
            QueryResult r = task.getValue();
            messagesArea.setText((r.failed() ? "✖ " : "✔ ") + truncate(sql) + "  →  "
                    + (r.failed() ? r.errorMessage() : r.summary()) + "\n" + messagesArea.getText());
            if (r.failed()) {
                statusLabel.getStyleClass().setAll("status-err");
                statusLabel.setText("✖ " + r.errorMessage());
                selectTab("Messages");
            } else {
                statusLabel.getStyleClass().setAll("status-2xx");
                statusLabel.setText("Applied · " + r.summary());
                refreshExplorer();
                if (onApplied != null) onApplied.run();
            }
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Apply failed: " + task.getException().getMessage());
        });
        runBg(task);
    }

    // ---- editable-result support (row delete now; in-grid UPDATE to follow) ------------------

    private static final java.util.regex.Pattern FROM_TABLE = java.util.regex.Pattern.compile(
            "(?is)\\bfrom\\s+[\"`\\[]?([A-Za-z_][A-Za-z0-9_]*)[\"`\\]]?");

    /**
     * Determines whether the just-run statement yields an editable grid: a single-table
     * {@code SELECT} (no join / comma-join / subquery) whose table has a primary key. Resolves the
     * table + PK off-thread and stores them for {@link #deleteRow}.
     */
    private void setEditContext(String statement) {
        editTable = null;
        editPk.clear();
        if (statement == null) return;
        String s = statement.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("select")) return;
        if (lower.contains(" join ") || lower.matches("(?s).*\\bfrom\\b[^;]*,.*")) return;   // multi-table → not editable
        java.util.regex.Matcher m = FROM_TABLE.matcher(s);
        if (!m.find()) return;
        String table = m.group(1);
        Task<List<String>> pkTask = new Task<>() {
            @Override protected List<String> call() throws Exception { return service.primaryKeyColumns(table); }
        };
        pkTask.setOnSucceeded(e -> {
            List<String> pk = pkTask.getValue();
            if (!pk.isEmpty() && currentColumns.containsAll(pk)) { editTable = table; editPk.addAll(pk); }
        });
        runBg(pkTask);
    }

    private void deleteRow(List<String> row) {
        if (row == null) return;
        if (editTable == null || editPk.isEmpty()) {
            statusLabel.getStyleClass().setAll("status-4xx");
            statusLabel.setText("This result isn't editable — run a simple SELECT from one table that has a primary key");
            return;
        }
        List<String> conds = new ArrayList<>();
        for (String pk : editPk) {
            int idx = currentColumns.indexOf(pk);
            if (idx < 0) { statusLabel.setText("Primary key column not in result — can't delete safely"); return; }
            String val = idx < row.size() ? row.get(idx) : null;
            conds.add(quoteIdent(pk) + " = " + sqlLiteral(val));
        }
        previewAndApply("DELETE FROM " + quoteIdent(editTable) + " WHERE " + String.join(" AND ", conds) + ";",
                "Delete 1 row from “" + editTable + "”?");
    }

    /** Renders a cell value as a SQL literal: NULL, a bare number, or a single-quoted, escaped string. */
    private static String sqlLiteral(String v) {
        if (v == null || "NULL".equals(v)) return "NULL";
        if (v.matches("-?\\d+(\\.\\d+)?")) return v;
        return "'" + v.replace("'", "''") + "'";
    }

    private static List<String> nullSafe(List<String> row) {
        List<String> out = new ArrayList<>(row.size());
        for (String c : row) out.add(c == null ? "" : c);
        return out;
    }

    private void generateStructure(List<String> names) {
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Generating structure for " + names.size() + " object(s)…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return service.exportSchema(names); }
        };
        task.setOnSucceeded(e -> { statusLabel.setText("Structure ready"); showStructureExport(task.getValue()); });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Structure export failed: " + task.getException().getMessage());
        });
        runBg(task);
    }

    /** Read-only, SQL-highlighted preview of the exported DDL with Copy and Save actions. */
    private void showStructureExport(String ddl) {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("Structure export");
        d.setHeaderText("CREATE-TABLE DDL — copy it to share, or save as .sql");
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        CodeArea preview = SqlHighlighter.area();
        preview.replaceText(ddl);
        preview.setEditable(false);
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(preview);
        scroll.setPrefSize(720, 460);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button copyBtn = new Button("Copy to clipboard");
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setOnAction(ev -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(ddl);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyBtn.setText("Copied ✓");
        });
        Button saveBtn = new Button("Save as .sql…");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(ev -> saveStructure(ddl));
        HBox actions = new HBox(8, copyBtn, saveBtn);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(8, actions, scroll);
        content.setPadding(new Insets(6));
        content.setPrefSize(740, 520);
        d.getDialogPane().setContent(content);
        d.setResizable(true);
        d.setOnShown(ev -> {
            if (d.getDialogPane().getScene() != null)
                com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene());
        });
        d.showAndWait();
    }

    private void saveStructure(String ddl) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save schema structure");
        fc.setInitialFileName("schema.sql");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL files", "*.sql"));
        File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), ddl, StandardCharsets.UTF_8);
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Structure saved → " + file.getName());
            logger.accept("Schema structure saved to " + file.getName());
        } catch (Exception ex) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Save failed: " + ex.getMessage());
        }
    }

    /** Checkbox picker for a subset of tables (shared by ER diagram + structure export). Null if cancelled. */
    private List<String> pickTables(List<String> tables, String title, String header) {
        Dialog<ButtonType> d = themedDialog(title, header);
        List<CheckBox> boxes = new ArrayList<>();
        VBox list = new VBox(4);
        list.setPadding(new Insets(4));
        for (String t : tables) {
            CheckBox cb = new CheckBox(t);
            cb.setSelected(true);
            boxes.add(cb);
            list.getChildren().add(cb);
        }
        CheckBox all = new CheckBox("Select all");
        all.setSelected(true);
        all.setOnAction(ev -> boxes.forEach(b -> b.setSelected(all.isSelected())));
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(Math.min(420, 30 + tables.size() * 26));
        VBox content = new VBox(8, all, new Separator(), sp);
        content.setPadding(new Insets(4));
        d.getDialogPane().setContent(content);
        if (d.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        List<String> out = new ArrayList<>();
        for (CheckBox cb : boxes) if (cb.isSelected()) out.add(cb.getText());
        return out;
    }

    private void buildErDiagram(List<String> tables) {
        statusLabel.setText("Building ER diagram…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return service.erDiagramMermaid(tables); }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("ER diagram ready");
            com.nexuslink.ui.markdown.DiagramView view = new com.nexuslink.ui.markdown.DiagramView();
            view.setDiagram(task.getValue());
            javafx.scene.Scene scene = new javafx.scene.Scene(view, 960, 720);
            com.nexuslink.ui.theme.ThemeManager.get().register(scene);
            javafx.stage.Stage stage = new javafx.stage.Stage();
            if (getScene() != null) stage.initOwner(getScene().getWindow());
            stage.setTitle("ER Diagram — scroll to zoom, drag to pan");
            stage.setScene(scene);
            stage.show();
            logger.accept("ER diagram generated");
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("ER diagram failed: " + task.getException().getMessage());
        });
        runBg(task);
    }

    private void createTableDialog() {
        if (!service.isConnected()) { statusLabel.setText("Connect first"); return; }
        Dialog<ButtonType> d = themedDialog("Create table", "Define a new table");
        TextField name = new TextField();
        name.setPromptText("table_name");
        TextArea cols = new TextArea("id INTEGER PRIMARY KEY,\nname TEXT,\ncreated_at TEXT");
        cols.setPrefRowCount(6);
        GridPane g = formGrid();
        g.addRow(0, new Label("Name:"), name);
        g.add(new Label("Columns:"), 0, 1);
        g.add(cols, 1, 1);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (name.getText().isBlank()) return;
            String body = cols.getText().replaceAll("\\s+", " ").trim().replaceAll(",\\s*$", "");
            runDdl("CREATE TABLE " + name.getText().trim() + " (" + body + ")");
        });
    }

    /** Add a column to a table — builds ALTER TABLE … ADD COLUMN and routes it through the preview gate. */
    private void addColumnDialog(String table) {
        Dialog<ButtonType> d = themedDialog("Add column", "Add a column to “" + table + "”");
        TextField name = new TextField();
        name.setPromptText("column_name");
        TextField type = new TextField("TEXT");
        GridPane g = formGrid();
        g.addRow(0, new Label("Name:"), name);
        g.addRow(1, new Label("Type:"), type);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (name.getText().isBlank() || type.getText().isBlank()) return;
            previewAndApply("ALTER TABLE " + quoteIdent(table) + " ADD COLUMN "
                            + quoteIdent(name.getText().trim()) + " " + type.getText().trim() + ";",
                    "Add column “" + name.getText().trim() + "” to “" + table + "”?");
        });
    }

    /** Rename a column — builds ALTER TABLE … RENAME COLUMN … TO … and routes it through the preview gate. */
    private void renameColumnDialog(String table, String column) {
        Dialog<ButtonType> d = themedDialog("Rename column", "Rename “" + column + "” on “" + table + "”");
        TextField newName = new TextField(column);
        GridPane g = formGrid();
        g.addRow(0, new Label("New name:"), newName);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            String nn = newName.getText().trim();
            if (nn.isBlank() || nn.equals(column)) return;
            previewAndApply("ALTER TABLE " + quoteIdent(table) + " RENAME COLUMN "
                            + quoteIdent(column) + " TO " + quoteIdent(nn) + ";",
                    "Rename column “" + column + "” to “" + nn + "”?");
        });
    }

    private void createIndexDialog() {
        if (!service.isConnected()) { statusLabel.setText("Connect first"); return; }
        Dialog<ButtonType> d = themedDialog("Create index", "Define a new index");
        TextField name = new TextField();
        name.setPromptText("idx_table_col");
        TextField table = new TextField();
        table.setPromptText("table");
        TextField columns = new TextField();
        columns.setPromptText("col1, col2");
        CheckBox unique = new CheckBox("Unique");
        GridPane g = formGrid();
        g.addRow(0, new Label("Name:"), name);
        g.addRow(1, new Label("Table:"), table);
        g.addRow(2, new Label("Columns:"), columns);
        g.add(unique, 1, 3);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (name.getText().isBlank() || table.getText().isBlank() || columns.getText().isBlank()) return;
            runDdl("CREATE " + (unique.isSelected() ? "UNIQUE " : "") + "INDEX " + name.getText().trim()
                    + " ON " + table.getText().trim() + " (" + columns.getText().trim() + ")");
        });
    }

    private void runDdl(String ddl) {
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Running DDL…");
        setEditorText(ddl + ";");
        logger.accept("SQL DDL → " + ddl);
        Task<QueryResult> task = new Task<>() {
            @Override protected QueryResult call() { return service.execute(ddl); }
        };
        task.setOnSucceeded(e -> {
            QueryResult r = task.getValue();
            if (r.failed()) {
                statusLabel.getStyleClass().setAll("status-err");
                statusLabel.setText("✖ " + r.errorMessage());
            } else {
                statusLabel.getStyleClass().setAll("status-2xx");
                statusLabel.setText("Done");
                refreshExplorer();
            }
        });
        task.setOnFailed(e -> statusLabel.setText("Error: " + task.getException().getMessage()));
        runBg(task);
    }

    private Dialog<ButtonType> themedDialog(String title, String header) {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle(title);
        d.setHeaderText(header);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setOnShown(ev -> {
            if (d.getDialogPane().getScene() != null)
                com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene());
        });
        return d;
    }

    private GridPane formGrid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(12, 4, 4, 4));
        return g;
    }

    private void saveCurrent() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) { statusLabel.setText("Enter a JDBC URL before saving"); return; }
        TextInputDialog dialog = new TextInputDialog(url);
        dialog.setTitle("Save connection");
        dialog.setHeaderText("Save this database connection for later");
        dialog.setContentText("Name:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            boolean hasUser = !userField.getText().isBlank();
            ConnectionProfile p = new ConnectionProfile(name.trim(), ConnectionProfile.Protocol.SQL, url)
                    .withUser(userField.getText())
                    .withAuth(hasUser ? AuthMethod.BASIC : AuthMethod.NONE);
            DriverInfo d = dbCombo.getValue();
            if (d != null) p.prop("driverId", d.id());
            if (!passField.getText().isBlank()) p.authProp("password", passField.getText());
            onSave.accept(p);
            logger.accept("Saved SQL connection: " + name.trim());
        });
    }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.setText("Connecting…");
        // Resolve ${VAR} against the active environment for the JDBC URL + credentials.
        String url = Env.resolve(urlField.getText().trim());
        String user = Env.resolve(userField.getText());
        String pass = Env.resolve(passField.getText());
        DriverInfo driver = dbCombo.getValue();
        String driverId = driver == null ? null : driver.id();
        java.util.Map<String, String> tlsProps = JdbcTlsParams.forDriver(driverId, tlsSpec());
        if (!tlsProps.isEmpty()) logger.accept("JDBC TLS params: " + tlsProps.keySet());
        logger.accept("JDBC connect → " + url);
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                service.connect(url, user, pass, tlsProps);
                return service.databaseInfo();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected: " + task.getValue());
            logger.accept("JDBC connected: " + task.getValue());
            connectBtn.setDisable(false);
            refreshExplorer();
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("JDBC connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    private void refreshExplorer() {
        explorer.setExplorer(new JdbcExplorer(service));
        explorer.load();
        cacheSchemaWords();
    }

    /** Loads table + column names off-thread so Ctrl+Space completion can offer them. */
    private void cacheSchemaWords() {
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception {
                java.util.LinkedHashSet<String> words = new java.util.LinkedHashSet<>();
                List<String> tables = new ArrayList<>();
                for (String t : service.listTables()) tables.add(t.replace("  (view)", "").trim());
                words.addAll(tables);
                for (String t : tables) {
                    try {
                        for (String c : service.describeTable(t)) {
                            String name = c.split("\\s{2,}", 2)[0].trim();   // "col  TYPE" → col
                            if (!name.isEmpty()) words.add(name);
                        }
                    } catch (Exception ignore) { /* skip a table we can't describe */ }
                }
                return new ArrayList<>(words);
            }
        };
        task.setOnSucceeded(e -> { schemaWords.clear(); schemaWords.addAll(task.getValue()); });
        runBg(task);
    }

    // ---- editor actions -------------------------------------------------------------------

    private void setEditorText(String s) {
        sqlEditor.replaceText(s == null ? "" : s);
        sqlEditor.moveTo(0);
        sqlEditor.requestFollowCaret();
    }

    /** Run: the selection if there is one, otherwise every statement in the editor. */
    private void runQuery() {
        String sel = sqlEditor.getSelectedText();
        runStatements((sel != null && !sel.isBlank()) ? sel : sqlEditor.getText());
    }

    /** Run selection, or the single statement surrounding the caret when nothing is selected. */
    private void runSelection() {
        String sel = sqlEditor.getSelectedText();
        runStatements((sel != null && !sel.isBlank()) ? sel : currentStatementAtCaret());
    }

    private void formatEditor() {
        String sel = sqlEditor.getSelectedText();
        if (sel != null && !sel.isBlank()) {
            sqlEditor.replaceSelection(SqlFormatter.format(sel));
        } else {
            String all = sqlEditor.getText();
            StringBuilder out = new StringBuilder();
            for (String s : splitStatements(all)) {
                if (out.length() > 0) out.append(";\n\n");
                out.append(SqlFormatter.format(s));
            }
            if (!all.isBlank()) out.append(";");
            setEditorText(out.toString());
        }
    }

    // ---- visual query help: EXPLAIN plan + charting -----------------------------------------

    /** Runs a dialect-appropriate EXPLAIN on the current statement and shows the plan in a dialog. */
    private void explainQuery() {
        if (!service.isConnected()) { statusLabel.setText("Connect to a database first"); return; }
        String sel = sqlEditor.getSelectedText();
        String base = (sel != null && !sel.isBlank()) ? sel : currentStatementAtCaret();
        if (base == null || base.isBlank()) { statusLabel.setText("Nothing to explain"); return; }
        String stmt = Env.resolve(base.trim());
        String explainSql = explainPrefix() + stmt;
        statusLabel.setText("Explaining…");
        Task<QueryResult> task = new Task<>() {
            @Override protected QueryResult call() { return service.execute(explainSql); }
        };
        task.setOnSucceeded(e -> {
            QueryResult r = task.getValue();
            if (r.failed()) {
                statusLabel.getStyleClass().setAll("status-err");
                statusLabel.setText("Explain failed: " + r.errorMessage());
            } else {
                statusLabel.getStyleClass().setAll("meta-label");
                statusLabel.setText("Query plan ready");
                showGridDialog("Query plan", explainSql, r);
            }
        });
        task.setOnFailed(e -> statusLabel.setText("Explain failed: " + task.getException().getMessage()));
        runBg(task);
    }

    /** SQLite needs EXPLAIN QUERY PLAN (bare EXPLAIN is bytecode); most others use plain EXPLAIN. */
    private String explainPrefix() {
        DriverInfo d = dbCombo.getValue();
        String id = d == null ? "" : d.id();
        return "sqlite".equals(id) ? "EXPLAIN QUERY PLAN " : "EXPLAIN ";
    }

    /** A read-only modal grid over a QueryResult (used for the query plan). */
    private void showGridDialog(String title, String header, QueryResult r) {
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle(title);
        d.setHeaderText(header.length() > 90 ? header.substring(0, 90) + "…" : header);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        TableView<List<String>> grid = new TableView<>();
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        for (int i = 0; i < r.columns().size(); i++) {
            final int col = i;
            TableColumn<List<String>, String> tc = new TableColumn<>(r.columns().get(i));
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().size() ? cd.getValue().get(col) : ""));
            tc.setPrefWidth(Math.max(120, Math.min(520, r.columns().get(col).length() * 8 + 60)));
            grid.getColumns().add(tc);
        }
        grid.getItems().setAll(r.rows());
        grid.setPrefSize(720, 420);
        d.getDialogPane().setContent(grid);
        d.setResizable(true);
        d.setOnShown(ev -> {
            if (d.getDialogPane().getScene() != null)
                com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene());
        });
        d.showAndWait();
    }

    /** Bar-chart the current result: pick a category column and a numeric column, then plot. */
    private void chartDialog() {
        if (currentColumns.isEmpty() || masterRows.isEmpty()) { statusLabel.setText("Run a query first — nothing to chart"); return; }
        Dialog<ButtonType> d = themedDialog("Chart result", "Plot two columns as a bar chart");
        ComboBox<String> category = new ComboBox<>(FXCollections.observableArrayList(currentColumns));
        ComboBox<String> value = new ComboBox<>(FXCollections.observableArrayList(currentColumns));
        category.getSelectionModel().select(0);
        value.getSelectionModel().select(Math.min(1, currentColumns.size() - 1));
        GridPane g = formGrid();
        g.addRow(0, new Label("Category (X):"), category);
        g.addRow(1, new Label("Value (Y):"), value);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> showChart(category.getValue(), value.getValue()));
    }

    private void showChart(String categoryCol, String valueCol) {
        int ci = currentColumns.indexOf(categoryCol), vi = currentColumns.indexOf(valueCol);
        if (ci < 0 || vi < 0) return;
        javafx.scene.chart.CategoryAxis x = new javafx.scene.chart.CategoryAxis();
        javafx.scene.chart.NumberAxis y = new javafx.scene.chart.NumberAxis();
        x.setLabel(categoryCol); y.setLabel(valueCol);
        javafx.scene.chart.BarChart<String, Number> chart = new javafx.scene.chart.BarChart<>(x, y);
        chart.getStyleClass().add("sql-view");   // so themed bars pick up the SQL accent
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        javafx.scene.chart.XYChart.Series<String, Number> series = new javafx.scene.chart.XYChart.Series<>();
        int plotted = 0, limit = 60;   // keep the chart legible
        for (List<String> row : sortedRows) {
            if (plotted >= limit) break;
            String label = ci < row.size() ? row.get(ci) : "";
            Double num = parseNumber(vi < row.size() ? row.get(vi) : null);
            if (num == null) continue;
            series.getData().add(new javafx.scene.chart.XYChart.Data<>(label, num));
            plotted++;
        }
        if (series.getData().isEmpty()) { statusLabel.setText("Column “" + valueCol + "” has no numeric values to plot"); return; }
        chart.getData().add(series);

        javafx.stage.Stage stage = new javafx.stage.Stage();
        if (getScene() != null) stage.initOwner(getScene().getWindow());
        stage.setTitle("Chart — " + valueCol + " by " + categoryCol);
        javafx.scene.Scene scene = new javafx.scene.Scene(chart, 820, 520);
        com.nexuslink.ui.theme.ThemeManager.get().register(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static Double parseNumber(String s) {
        if (s == null || s.isBlank() || "NULL".equals(s)) return null;
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Comment or uncomment the current line with a leading {@code -- }. */
    private void toggleLineComment() {
        int para = sqlEditor.getCurrentParagraph();
        String line = sqlEditor.getParagraph(para).getText();
        int start = sqlEditor.getAbsolutePosition(para, 0);
        String trimmed = line.stripLeading();
        int indent = line.length() - trimmed.length();
        if (trimmed.startsWith("-- ")) {
            sqlEditor.replaceText(start + indent, start + indent + 3, "");
        } else if (trimmed.startsWith("--")) {
            sqlEditor.replaceText(start + indent, start + indent + 2, "");
        } else {
            sqlEditor.insertText(start + indent, "-- ");
        }
    }

    /** The ;-bounded statement containing the caret. */
    private String currentStatementAtCaret() {
        String text = sqlEditor.getText();
        int caret = sqlEditor.getCaretPosition();
        int from = text.lastIndexOf(';', Math.max(0, caret - 1)) + 1;
        int to = text.indexOf(';', caret);
        if (to < 0) to = text.length();
        return text.substring(Math.min(from, text.length()), Math.min(to, text.length()));
    }

    /**
     * Splits a SQL block into non-empty statements, delegating to the DB module's
     * {@link com.nexuslink.protocol.db.SqlScriptSplitter} so strings, comments and dollar-quoting
     * are handled the same way the rest of the app splits scripts.
     */
    private List<String> splitStatements(String block) {
        return com.nexuslink.protocol.db.SqlScriptSplitter.split(block);
    }

    // ---- execution ------------------------------------------------------------------------

    /** Result of running a block: which result to display, plus a per-statement message log. */
    private record RunOutcome(QueryResult display, String displayStmt, String messages) {}

    private void runStatements(String block) {
        if (block == null || block.isBlank()) return;
        List<String> statements = splitStatements(block);
        if (statements.isEmpty()) return;
        resultStatus.getStyleClass().setAll("meta-label");
        resultStatus.setText("Running " + statements.size() + " statement" + (statements.size() == 1 ? "" : "s") + "…");
        logger.accept("SQL → " + truncate(statements.get(0)) + (statements.size() > 1 ? "  (+" + (statements.size() - 1) + " more)" : ""));

        Task<RunOutcome> task = new Task<>() {
            @Override protected RunOutcome call() {
                QueryResult display = null;
                String displayStmt = null;
                StringBuilder msg = new StringBuilder();
                for (String s : statements) {
                    String stmt = Env.resolve(s);
                    QueryResult r = service.execute(stmt);
                    msg.append(r.failed() ? "✖ " : "✔ ").append(truncate(stmt)).append("  →  ")
                       .append(r.failed() ? r.errorMessage() : r.summary()).append('\n');
                    // Prefer the last statement that returned a grid; otherwise keep the last outcome.
                    if (r.failed()) { display = r; displayStmt = stmt; break; }
                    if (r.isResultSet() || display == null) { display = r; displayStmt = stmt; }
                }
                return new RunOutcome(display, displayStmt, msg.toString());
            }
        };
        task.setOnSucceeded(e -> {
            RunOutcome o = task.getValue();
            messagesArea.setText(o.messages());
            renderResult(o.display());
            setEditContext(o.displayStmt());
            if (o.display() != null && o.display().failed()) selectTab("Messages");
            recordQueryHistory(o.displayStmt(), o.display());
        });
        task.setOnFailed(e -> {
            resultStatus.getStyleClass().setAll("status-err");
            resultStatus.setText("Error: " + task.getException().getMessage());
        });
        runBg(task);
    }

    private void selectTab(String text) {
        for (Tab t : resultTabs.getTabs()) if (text.equals(t.getText())) { resultTabs.getSelectionModel().select(t); return; }
    }

    /** Records an executed statement into the shared history store (summary + replayable detail JSON). */
    private void recordQueryHistory(String statement, QueryResult r) {
        if (statement == null || r == null) return;
        try {
            String url = urlField.getText().trim();
            String summary = truncate(statement) + " → " + r.summary();
            var detail = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            detail.put("url", url);
            detail.put("sql", statement);
            historyRecorder.accept(com.nexuslink.core.history.HistoryEntry.newSql(
                    summary, r.durationMs(), detail.toString()));
        } catch (Exception ignored) {
            // History is best-effort; never let a recording error break the query flow.
        }
    }

    private void renderResult(QueryResult r) {
        if (r == null) return;
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
        masterRows.clear();
        currentColumns.clear();
        if (!r.isResultSet()) {
            setStats(0, 0, r.durationMs());
            return;
        }
        currentColumns.addAll(r.columns());

        for (int i = 0; i < r.columns().size(); i++) {
            final int col = i;
            String type = i < r.columnTypes().size() ? r.columnTypes().get(i) : "";
            TableColumn<List<String>, String> tc = new TableColumn<>();
            tc.setGraphic(columnHeader(r.columns().get(i), type));
            tc.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                    col < cd.getValue().size() ? cd.getValue().get(col) : ""));
            tc.setCellFactory(c -> editableCell());
            tc.setOnEditCommit(ev -> onCellEdit(ev.getRowValue(), col, ev.getNewValue()));
            tc.setPrefWidth(contentWidth(r, col, type));
            resultGrid.getColumns().add(tc);
        }
        resultGrid.setEditable(true);   // edits are gated in onCellEdit (single-table + PK only)
        masterRows.setAll(r.rows());
        applyRowFilter(filterField.getText());
        setStats(r.rowCount(), r.columns().size(), r.durationMs());
        selectTab("Result");
    }

    /** Pref width for a column: the widest of header name, type label, and a sample of cell values. */
    private double contentWidth(QueryResult r, int col, String type) {
        int widest = Math.max(r.columns().get(col).length(), type == null ? 0 : type.length());
        int sample = Math.min(r.rows().size(), 200);
        for (int i = 0; i < sample; i++) {
            List<String> row = r.rows().get(i);
            if (col < row.size() && row.get(col) != null) widest = Math.max(widest, row.get(col).length());
        }
        return Math.max(70, Math.min(360, widest * 7.5 + 26));   // ~7.5px per char, clamped
    }

    /** A two-line column header: bold name over a faint, mono type label. */
    private VBox columnHeader(String name, String type) {
        Label n = new Label(name);
        n.getStyleClass().add("col-name");
        VBox box = new VBox(n);
        if (type != null && !type.isBlank()) {
            Label t = new Label(type);
            t.getStyleClass().add("col-type");
            box.getChildren().add(t);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * An editable text cell (double-click to edit) that also renders SQL NULLs (the literal "NULL"
     * value) in a faint italic style when not being edited. Commits are gated in {@link #onCellEdit}.
     */
    private TableCell<List<String>, String> editableCell() {
        return new javafx.scene.control.cell.TextFieldTableCell<>(new javafx.util.converter.DefaultStringConverter()) {
            @Override public void updateItem(String v, boolean empty) {
                super.updateItem(v, empty);
                getStyleClass().remove("null-cell");
                if (!empty && "NULL".equals(v) && !isEditing()
                        && !getStyleClass().contains("null-cell")) {
                    getStyleClass().add("null-cell");
                }
            }
        };
    }

    /** Handles a committed cell edit: builds a targeted UPDATE and runs it through the preview gate. */
    private void onCellEdit(List<String> row, int col, String newVal) {
        String oldVal = col < row.size() ? row.get(col) : null;
        if (java.util.Objects.equals(oldVal, newVal)) { resultGrid.refresh(); return; }   // no-op edit
        if (editTable == null || editPk.isEmpty()) {
            statusLabel.getStyleClass().setAll("status-4xx");
            statusLabel.setText("This result isn't editable — run a simple SELECT from one table that has a primary key");
            resultGrid.refresh();   // discard the visual edit
            return;
        }
        List<String> conds = new ArrayList<>();
        for (String pk : editPk) {
            int idx = currentColumns.indexOf(pk);
            if (idx < 0) { statusLabel.setText("Primary key column not in result — can't update safely"); resultGrid.refresh(); return; }
            conds.add(quoteIdent(pk) + " = " + sqlLiteral(idx < row.size() ? row.get(idx) : null));
        }
        String colName = currentColumns.get(col);
        String sql = "UPDATE " + quoteIdent(editTable) + " SET " + quoteIdent(colName) + " = " + sqlLiteral(newVal)
                + " WHERE " + String.join(" AND ", conds) + ";";
        previewAndApply(sql, "Update “" + colName + "” in “" + editTable + "”?",
                () -> { row.set(col, newVal); resultGrid.refresh(); });
    }

    // ---- autocomplete ---------------------------------------------------------------------

    /** Ctrl+Space: suggest SQL keywords + schema tables/columns matching the word before the caret. */
    private void showCompletions() {
        int caret = sqlEditor.getCaretPosition();
        String text = sqlEditor.getText();
        int start = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, caret);
        String lower = prefix.toLowerCase(Locale.ROOT);

        java.util.LinkedHashSet<String> pool = new java.util.LinkedHashSet<>(schemaWords);
        pool.addAll(SqlHighlighter.vocabulary());
        List<String> matches = new ArrayList<>();
        for (String w : pool) {
            if (prefix.isBlank() || w.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(w);
            if (matches.size() >= 40) break;
        }
        completionPopup.getItems().clear();
        if (matches.isEmpty()) { completionPopup.hide(); return; }
        final int wordStart = start;
        for (String w : matches) {
            MenuItem mi = new MenuItem(w);
            mi.setOnAction(ev -> { sqlEditor.replaceText(wordStart, caret, w); sqlEditor.requestFocus(); });
            completionPopup.getItems().add(mi);
        }
        sqlEditor.getCaretBounds().ifPresent(b ->
                completionPopup.show(sqlEditor, b.getMaxX(), b.getMaxY()));
    }

    private static boolean isWordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    /** Live row filter: keeps rows where any cell contains {@code text} (case-insensitive). */
    private void applyRowFilter(String text) {
        if (text == null || text.isBlank()) {
            filteredRows.setPredicate(r -> true);
            return;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        filteredRows.setPredicate(row -> {
            for (String cell : row) {
                if (cell != null && cell.toLowerCase(Locale.ROOT).contains(needle)) return true;
            }
            return false;
        });
    }

    /** Exports the currently displayed rows (after sort + filter) as JSON or CSV. */
    private void exportResults(boolean asJson) {
        if (resultGrid.getColumns().isEmpty()) { resultStatus.setText("No results to export"); return; }
        List<String> columns = new ArrayList<>();
        for (TableColumn<List<String>, ?> c : resultGrid.getColumns()) columns.add(c.getText());
        List<List<String>> rows = new ArrayList<>(sortedRows);

        FileChooser fc = new FileChooser();
        fc.setTitle("Export query results");
        String ext = asJson ? "json" : "csv";
        fc.setInitialFileName("query-results." + ext);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext.toUpperCase(Locale.ROOT) + " files", "*." + ext));
        File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;

        String content = asJson ? ResultGridExporter.toJson(columns, rows)
                : ResultGridExporter.toCsv(columns, rows);
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            resultStatus.getStyleClass().setAll("status-2xx");
            resultStatus.setText("Exported " + rows.size() + " row(s) → " + file.getName());
            logger.accept("SQL export: wrote " + rows.size() + " row(s) to " + file.getName());
        } catch (Exception ex) {
            resultStatus.getStyleClass().setAll("status-err");
            resultStatus.setText("Export failed: " + ex.getMessage());
            logger.accept("SQL export FAILED: " + ex.getMessage());
        }
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
