package com.nexuslink.ui.main;

import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.core.connection.ProfileValidator;
import com.nexuslink.core.connection.ConnectionStore;
import com.nexuslink.core.di.AppContext;
import com.nexuslink.core.env.EnvironmentService;
import com.nexuslink.core.history.HistoryEntry;
import com.nexuslink.core.history.HistoryStore;
import com.nexuslink.ui.agent.AgentView;
import com.nexuslink.ui.azure.AzureBlobView;
import com.nexuslink.ui.connection.ConnectionsPanel;
import com.nexuslink.ui.env.EnvironmentManagerView;
import com.nexuslink.ui.ftp.FtpView;
import com.nexuslink.ui.cert.CertificateManagerView;
import com.nexuslink.ui.gcs.GcsView;
import com.nexuslink.ui.graphql.GraphQLView;
import com.nexuslink.ui.grpc.GrpcView;
import com.nexuslink.ui.ldap.LdapView;
import com.nexuslink.ui.snmp.SnmpView;
import com.nexuslink.ui.help.HelpDialog;
import com.nexuslink.ui.icons.Icons;
import com.nexuslink.ui.kafka.KafkaView;
import com.nexuslink.ui.llm.LlmTesterView;
import com.nexuslink.ui.mcp.McpInspectorView;
import com.nexuslink.ui.mongo.MongoClientView;
import com.nexuslink.ui.mqtt.MqttView;
import com.nexuslink.ui.rabbitmq.RabbitMqView;
import com.nexuslink.ui.redis.RedisView;
import com.nexuslink.ui.rest.RestClientView;
import com.nexuslink.ui.s3.S3View;
import com.nexuslink.ui.sftp.SftpView;
import com.nexuslink.ui.sql.SqlClientView;
import com.nexuslink.ui.sse.SseView;
import com.nexuslink.ui.theme.ThemeManager;
import com.nexuslink.ui.vault.VaultSession;
import com.nexuslink.ui.ws.WebSocketView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The NexusLink workspace shell: menu bar + global search, a connection tree on the left,
 * a tabbed workspace in the centre, a collapsible log panel at the bottom, and a status bar.
 * <p>
 * This is the real application window (replaces the Session-1 placeholder launcher).
 */
public final class MainWindow {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final BorderPane root = new BorderPane();
    private TabPane workspace;
    private TextArea logArea;
    private TitledPane bottomPane;
    private Label statusConnections;
    private Label vaultStatus;
    private int newTabCounter = 0;

    private HistoryStore historyStore;
    private HistoryPanel historyPanel;
    private TabPane bottomTabs;
    private MenuItem themeItem;
    private Menu fileMenu;
    private MenuItem quitItem;
    private VBox protocolButtons;
    private final ProtocolPrefs protocolPrefs = new ProtocolPrefs();
    private final ConnectionStore connectionStore = new ConnectionStore();
    private final EnvironmentService environmentService = new EnvironmentService();
    private final com.nexuslink.core.metrics.MetricsCollector metricsCollector = new com.nexuslink.core.metrics.MetricsCollector();
    private ConnectionsPanel connectionsPanel;

    {
        AppContext.get().registerInstance(EnvironmentService.class, environmentService);
        AppContext.get().registerInstance(com.nexuslink.core.metrics.MetricsCollector.class, metricsCollector);
    }

    public Scene createScene() {
        initHistoryStore();
        root.getStyleClass().add("root");
        root.setTop(buildTopBar());
        root.setBottom(buildStatusBar());   // build status bar first — center init references it
        root.setCenter(buildCenter());
        rebuildProtocols();                  // populate File menu + sidebar from enabled protocols
        openRestTab();                       // open the initial tab after all panels exist

        Scene scene = new Scene(root, 1180, 760);
        ThemeManager.get().register(scene, "/com/nexuslink/ui/css/rest-client.css");

        scene.getAccelerators().put(KeyCombination.keyCombination("F1"),
                () -> HelpDialog.open("getting-started"));
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+T"),
                this::openRestTab);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Enter"),
                this::sendCurrent);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+`"),
                this::toggleLog);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Shift+T"),
                this::toggleTheme);

        log("NexusLink started. Press F1 for help, Ctrl+T for a new REST tab.");

        // Demo hook: open the AI tabs (MCP + LLM) on startup for screenshots
        if ("1".equals(System.getenv("NEXUSLINK_OPEN_AI"))) {
            openLlmTab();
            openMcpTab();
        }

        // Demo hook: open every protocol tab; auto-run the SQL demo
        if ("1".equals(System.getenv("NEXUSLINK_OPEN_ALL"))) {
            openWebSocketTab();
            openMcpTab();
            openLlmTab();
            SqlClientView sql = new SqlClientView();
            sql.setLogger(this::log);
            Tab tab = new Tab("SQL " + (++newTabCounter), sql);
            tab.setClosable(true);
            workspace.getTabs().add(tab);
            workspace.getSelectionModel().select(tab);
            javafx.application.Platform.runLater(sql::runDemo);
        }

        // Demo hook: auto-send the first request on startup (for screenshots/smoke test)
        if (System.getProperty("nexuslink.autosend") != null
                || "1".equals(System.getenv("NEXUSLINK_AUTOSEND"))) {
            javafx.application.Platform.runLater(() -> {
                sendCurrent();
                bottomTabs.getSelectionModel().select(1); // reveal History tab
            });
        }
        return scene;
    }

    // ---- Top: menu + global search ----

    private VBox buildTopBar() {
        MenuBar menuBar = new MenuBar();
        fileMenu = new Menu("File", Icons.of("file", 14));
        quitItem = new MenuItem("Quit");
        quitItem.setOnAction(e -> javafx.application.Platform.exit());
        // File menu protocol items are populated (and filtered by enabled protocols) in rebuildProtocols()
        Menu file = fileMenu;

        Menu ai = new Menu("AI", Icons.of("ai", 14));
        MenuItem mcpItem = new MenuItem("MCP Inspector", Icons.of("mcp", 14));
        mcpItem.setOnAction(e -> openMcpTab());
        MenuItem llmItem = new MenuItem("Agent / LLM Tester", Icons.of("ai", 14));
        llmItem.setOnAction(e -> openLlmTab());
        ai.getItems().addAll(mcpItem, llmItem);

        Menu view = new Menu("View", Icons.of("view", 14));
        MenuItem toggleLog = new MenuItem("Toggle Log Panel");
        toggleLog.setOnAction(e -> toggleLog());
        MenuItem protocolsItem = new MenuItem("Protocols…");
        protocolsItem.setOnAction(e -> showProtocolsDialog());
        themeItem = new MenuItem(themeMenuLabel());
        themeItem.setOnAction(e -> toggleTheme());
        ThemeManager.get().addListener(() -> themeItem.setText(themeMenuLabel()));
        view.getItems().addAll(toggleLog, protocolsItem, new SeparatorMenuItem(), themeItem);

        Menu tools = new Menu("Tools", Icons.of("tools", 14));
        MenuItem unlockVault = new MenuItem("Unlock Vault…");
        unlockVault.setOnAction(e -> { if (VaultSession.get().ensureUnlocked(owner())) updateVaultStatus(); });
        MenuItem lockVault = new MenuItem("Lock Vault");
        lockVault.setOnAction(e -> { VaultSession.get().lock(); log("Vault locked."); });
        MenuItem certManager = new MenuItem("Certificate Manager…");
        certManager.setOnAction(e -> openCertManagerTab());
        MenuItem environments = new MenuItem("Environments…");
        environments.setOnAction(e -> openEnvironmentsTab());
        MenuItem metrics = new MenuItem("Metrics Dashboard…");
        metrics.setOnAction(e -> openMetricsTab());
        tools.getItems().addAll(unlockVault, lockVault, new SeparatorMenuItem(), certManager, environments, metrics);

        Menu help = new Menu("Help", Icons.of("help", 14));
        MenuItem helpIndex = new MenuItem("Help Index  (F1)", Icons.of("help", 14));
        helpIndex.setOnAction(e -> HelpDialog.open("getting-started"));
        MenuItem shortcuts = new MenuItem("Keyboard Shortcuts");
        shortcuts.setOnAction(e -> HelpDialog.open("keyboard-shortcuts"));
        help.getItems().addAll(helpIndex, shortcuts);

        menuBar.getMenus().addAll(file, buildEditMenu(), view,
                new Menu("Connection", Icons.of("connection", 14)), tools, ai, help);
        HBox.setHgrow(menuBar, Priority.ALWAYS);

        TextField search = new TextField();
        search.setPromptText("🔍  Search…");
        search.getStyleClass().add("global-search");
        search.setPrefWidth(240);

        HBox top = new HBox(menuBar, search);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 8, 0, 0));
        HBox.setMargin(search, new Insets(4));
        return new VBox(top);
    }

    // ---- Center: sidebar | workspace / log ----

    private SplitPane buildCenter() {
        SplitPane horizontal = new SplitPane();
        horizontal.getItems().addAll(buildSidebar(), buildWorkspaceWithLog());
        horizontal.setDividerPositions(0.2);
        SplitPane.setResizableWithParent(horizontal.getItems().get(0), false);
        return horizontal;
    }

    private VBox buildSidebar() {
        Label title = new Label("CONNECTIONS");
        title.getStyleClass().add("sidebar-title");

        connectionsPanel = new ConnectionsPanel(connectionStore);
        connectionsPanel.setOnOpen(this::openProfile);
        VBox.setVgrow(connectionsPanel, Priority.ALWAYS);

        protocolButtons = new VBox(6);
        VBox.setMargin(protocolButtons, new Insets(8));
        ScrollPane buttonScroll = new ScrollPane(protocolButtons);
        buttonScroll.setFitToWidth(true);
        buttonScroll.getStyleClass().add("protocol-scroll");
        buttonScroll.setMaxHeight(320);
        // Buttons are populated (and filtered by enabled protocols) in rebuildProtocols()

        VBox sidebar = new VBox(title, connectionsPanel, buttonScroll);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setMinWidth(180);
        return sidebar;
    }

    private SplitPane buildWorkspaceWithLog() {
        workspace = new TabPane();
        workspace.getStyleClass().add("workspace-tabs");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.getStyleClass().add("log-panel");
        logArea.setPrefRowCount(6);

        historyPanel = new HistoryPanel(historyStore, this::replayHistory);

        bottomTabs = new TabPane();
        bottomTabs.getStyleClass().add("editor-tabs");
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        bottomTabs.getTabs().addAll(
                new Tab("Log", logArea),
                new Tab("History (" + historyStore.count() + ")", historyPanel));

        bottomPane = new TitledPane("Activity", bottomTabs);
        bottomPane.setExpanded(true);
        bottomPane.getStyleClass().add("log-titled");

        SplitPane vertical = new SplitPane();
        vertical.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vertical.getItems().addAll(workspace, bottomPane);
        vertical.setDividerPositions(0.74);
        return vertical;
    }

    private void initHistoryStore() {
        Path dir = Path.of(System.getProperty("user.home"), ".nexuslink");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception ignored) { /* fall through — store will surface errors */ }
        historyStore = new HistoryStore(dir.resolve("history.db").toString());
    }

    private BorderPane buildStatusBar() {
        statusConnections = new Label("0 connections");
        Label spacer = new Label();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        vaultStatus = new Label();
        vaultStatus.getStyleClass().add("meta-label");
        vaultStatus.setOnMouseClicked(e -> {
            if (VaultSession.get().isUnlocked()) { VaultSession.get().lock(); log("Vault locked."); }
            else if (VaultSession.get().ensureUnlocked(owner())) updateVaultStatus();
        });
        Label version = new Label("v1.0.0-SNAPSHOT  ·  RouteForge");
        HBox bar = new HBox(statusConnections, spacer, vaultStatus, new Label("    "), version);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        VaultSession.get().setOnStateChange(this::updateVaultStatus);
        updateVaultStatus();
        return new BorderPane(bar);
    }

    private void updateVaultStatus() {
        if (vaultStatus == null) return;
        boolean unlocked = VaultSession.get().isUnlocked();
        vaultStatus.setText(unlocked ? "🔓 Vault unlocked" : "🔒 Vault locked");
    }

    // ---- Actions ----

    private RestClientView openRestTab() {
        RestClientView view = new RestClientView();
        view.setLogger(this::log);
        view.setHistoryRecorder(this::recordHistory);
        view.setOnSave(this::saveConnection);
        Tab tab = new Tab("REST " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
        return view;
    }

    private WebSocketView openWebSocketTab() {
        WebSocketView view = new WebSocketView();
        view.setLogger(this::log);
        addTab("WS " + (++newTabCounter), view);
        return view;
    }

    private SseView openSseTab() {
        SseView view = new SseView();
        view.setLogger(this::log);
        addTab("SSE " + (++newTabCounter), view);
        return view;
    }

    private GraphQLView openGraphQLTab() {
        GraphQLView view = new GraphQLView();
        view.setLogger(this::log);
        addTab("GraphQL " + (++newTabCounter), view);
        return view;
    }

    private GrpcView openGrpcTab() {
        GrpcView view = new GrpcView();
        view.setLogger(this::log);
        addTab("gRPC " + (++newTabCounter), view);
        return view;
    }

    private S3View openS3Tab() {
        S3View view = new S3View();
        view.setLogger(this::log);
        addTab("S3 " + (++newTabCounter), view);
        return view;
    }

    private KafkaView openKafkaTab() {
        KafkaView view = new KafkaView();
        view.setLogger(this::log);
        addTab("Kafka " + (++newTabCounter), view);
        return view;
    }

    private MqttView openMqttTab() {
        MqttView view = new MqttView();
        view.setLogger(this::log);
        addTab("MQTT " + (++newTabCounter), view);
        return view;
    }

    private RabbitMqView openRabbitMqTab() {
        RabbitMqView view = new RabbitMqView();
        view.setLogger(this::log);
        addTab("RabbitMQ " + (++newTabCounter), view);
        return view;
    }

    private LdapView openLdapTab() {
        LdapView view = new LdapView();
        view.setLogger(this::log);
        addTab("LDAP " + (++newTabCounter), view);
        return view;
    }

    private SnmpView openSnmpTab() {
        SnmpView view = new SnmpView();
        view.setLogger(this::log);
        addTab("SNMP " + (++newTabCounter), view);
        return view;
    }

    private RedisView openRedisTab() {
        RedisView view = new RedisView();
        view.setLogger(this::log);
        addTab("Redis " + (++newTabCounter), view);
        return view;
    }

    private AzureBlobView openAzureTab() {
        AzureBlobView view = new AzureBlobView();
        view.setLogger(this::log);
        addTab("Azure " + (++newTabCounter), view);
        return view;
    }

    private GcsView openGcsTab() {
        GcsView view = new GcsView();
        view.setLogger(this::log);
        addTab("GCS " + (++newTabCounter), view);
        return view;
    }

    private SftpView openSftpTab() {
        SftpView view = new SftpView();
        view.setLogger(this::log);
        addTab("SFTP " + (++newTabCounter), view);
        return view;
    }

    private FtpView openFtpTab() {
        FtpView view = new FtpView();
        view.setLogger(this::log);
        addTab("FTP " + (++newTabCounter), view);
        return view;
    }

    private SqlClientView openSqlTab() {
        SqlClientView view = new SqlClientView();
        view.setLogger(this::log);
        view.setOnSave(this::saveConnection);
        addTab("SQL " + (++newTabCounter), view);
        return view;
    }

    private MongoClientView openMongoTab() {
        MongoClientView view = new MongoClientView();
        view.setLogger(this::log);
        view.setOnSave(this::saveConnection);
        addTab("Mongo " + (++newTabCounter), view);
        return view;
    }

    private McpInspectorView openMcpTab() {
        McpInspectorView view = new McpInspectorView();
        view.setLogger(this::log);
        addTab("MCP " + (++newTabCounter), view);
        return view;
    }

    private LlmTesterView openLlmTab() {
        LlmTesterView view = new LlmTesterView();
        view.setLogger(this::log);
        addTab("LLM " + (++newTabCounter), view);
        return view;
    }

    private AgentView openAgentTab() {
        AgentView view = new AgentView();
        view.setLogger(this::log);
        addTab("Agent " + (++newTabCounter), view);
        return view;
    }

    private CertificateManagerView openCertManagerTab() {
        CertificateManagerView view = new CertificateManagerView();
        view.setLogger(this::log);
        addTab("Certificates " + (++newTabCounter), view);
        return view;
    }

    private EnvironmentManagerView openEnvironmentsTab() {
        EnvironmentManagerView view = new EnvironmentManagerView(environmentService);
        view.setLogger(this::log);
        addTab("Environments " + (++newTabCounter), view);
        return view;
    }

    private com.nexuslink.ui.metrics.MetricsView openMetricsTab() {
        com.nexuslink.ui.metrics.MetricsView view = new com.nexuslink.ui.metrics.MetricsView();
        addTab("Metrics " + (++newTabCounter), view);
        return view;
    }

    private void addTab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
    }

    /** Opens a saved/sample connection in the appropriate protocol tab, pre-filled. */
    private void openProfile(ConnectionProfile p) {
        ConnectionProfile d = decrypted(p);   // resolve any vault refs into plaintext authProps
        switch (p.protocol) {
            case REST -> openRestTab().applyProfile(d);
            case WEBSOCKET -> openWebSocketTab().prefill(d.target);
            case SSE -> openSseTab().prefill(d.target);
            case GRAPHQL -> openGraphQLTab().prefill(d.target);
            case GRPC -> openGrpcTab().prefill(d.target);
            case SQL -> openSqlTab().prefill(d.target, d.username, d.authProps.get("password"));
            case MONGO -> openMongoTab().prefill(mongoTarget(d));
            case S3 -> openS3Tab().prefill(d.target, d.username, d.authProps.get("secretKey"));
            case KAFKA -> openKafkaTab().prefill(d.target);
            case MQTT -> openMqttTab().prefill(d.target, d.username, d.authProps.get("password"));
            case REDIS -> openRedisTab().prefill(d.target);
            case AZURE_BLOB -> openAzureTab().prefill(d.target);
            case GCS -> openGcsTab().prefill(d.target);
            case SFTP -> openSftpTab().prefill(d.target, d.username, d.authProps.get("password"));
            case FTP -> openFtpTab().prefill(d.target, d.username, d.authProps.get("password"));
            case MCP -> openMcpTab().prefill(d.target, d.properties.get("transport"));
            case LLM -> openLlmTab();
            default -> {
                log(p.protocol + " connector is on the roadmap — '" + p.name + "' can't be opened yet.");
                return;
            }
        }
        log("Opened connection: " + p.name);
    }

    /** authProps keys whose values are secrets and must be stored in the vault, not plaintext. */
    private static final java.util.List<String> SECRET_KEYS =
            java.util.List.of("password", "token", "apiKeyValue", "clientSecret", "secretKey");

    /** Saves a connection, moving any plaintext secrets into the encrypted vault first. */
    private void saveConnection(ConnectionProfile p) {
        ProfileValidator.Result check = ProfileValidator.validate(p);
        if (!check.valid()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Can't save connection");
            alert.setHeaderText("'" + p.name + "' has validation problems");
            alert.setContentText(String.join("\n", check.errors()));
            alert.showAndWait();
            log("Save rejected — " + check.summary());
            return;
        }
        for (String key : SECRET_KEYS) {
            String value = p.authProps.remove(key);
            if (value != null && !value.isBlank()) {
                String ref = VaultSession.get().storeSecret("nl-" + key, value, owner());
                if (ref != null) p.authProps.put(key + "Ref", ref);
                else { p.authProps.put(key, value); log("Vault locked — '" + p.name + "' saved with " + key + " unprotected."); }
            }
        }
        // Mongo connection string containing credentials → vault ref + masked display target
        if (p.protocol == ConnectionProfile.Protocol.MONGO && hasInlineCredentials(p.target)) {
            String ref = VaultSession.get().storeSecret("mongo-uri", p.target, owner());
            if (ref != null) { p.authProps.put("targetRef", ref); p.target = maskUri(p.target); }
        }
        connectionsPanel.saveProfile(p);
        log("Saved connection: " + p.name);
    }

    /** Returns a copy of {@code p} with every {@code *Ref} secret resolved into its plaintext key. */
    private ConnectionProfile decrypted(ConnectionProfile p) {
        ConnectionProfile c = new ConnectionProfile(p.name, p.protocol, p.target);
        c.id = p.id;
        c.username = p.username;
        c.auth = p.auth;
        c.sample = p.sample;
        c.properties.putAll(p.properties);
        c.authProps.putAll(p.authProps);
        for (String key : SECRET_KEYS) {
            String ref = c.authProps.remove(key + "Ref");
            if (ref != null) VaultSession.get().resolve(ref, owner()).ifPresent(v -> c.authProps.put(key, v));
        }
        return c;
    }

    private String mongoTarget(ConnectionProfile p) {
        String ref = p.authProps.get("targetRef");
        if (ref != null) return VaultSession.get().resolve(ref, owner()).orElse(p.target);
        return p.target;
    }

    private static boolean hasInlineCredentials(String uri) {
        return uri != null && uri.matches("(?i).*://[^/@]+:[^/@]+@.*");
    }

    private static String maskUri(String uri) {
        return uri.replaceAll("(://[^/@:]+):[^/@]+@", "$1:***@");
    }

    private javafx.stage.Window owner() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ---- Edit menu: clipboard actions routed to the focused text control ----

    private Menu buildEditMenu() {
        Menu edit = new Menu("Edit", Icons.of("edit", 14));
        edit.getItems().addAll(
                editItem("Undo", "Shortcut+Z", "undo"),
                editItem("Redo", "Shortcut+Shift+Z", "redo"),
                new SeparatorMenuItem(),
                editItem("Cut", "Shortcut+X", "cut"),
                editItem("Copy", "Shortcut+C", "copy"),
                editItem("Paste", "Shortcut+V", "paste"),
                new SeparatorMenuItem(),
                editItem("Select All", "Shortcut+A", "selectAll"));
        return edit;
    }

    private MenuItem editItem(String label, String accelerator, String action) {
        MenuItem mi = new MenuItem(label);
        mi.setAccelerator(KeyCombination.keyCombination(accelerator));
        mi.setOnAction(e -> editAction(action));
        return mi;
    }

    private void editAction(String action) {
        javafx.scene.Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        if (focused instanceof TextInputControl t) {
            switch (action) {
                case "undo" -> { if (t.isUndoable()) t.undo(); }
                case "redo" -> { if (t.isRedoable()) t.redo(); }
                case "cut" -> t.cut();
                case "copy" -> t.copy();
                case "paste" -> t.paste();
                case "selectAll" -> t.selectAll();
                default -> { }
            }
        } else if (focused instanceof javafx.scene.web.WebView wv) {
            // For rendered Help/diagrams: copy the current selection from the page.
            try { wv.getEngine().executeScript("document.execCommand('" + action + "')"); } catch (Exception ignored) { }
        }
    }

    private Button sidebarButton(String label, String icon, Runnable action) {
        Button b = new Button(label, Icons.of(icon, 15));
        b.getStyleClass().add("btn-secondary");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setGraphicTextGap(8);
        b.setOnAction(e -> action.run());
        return b;
    }

    // ---- Protocol catalog (data-driven so it can be enabled/disabled per user) ----

    private record ProtocolDef(String id, String label, String menuLabel, String icon, Runnable opener) {}

    private java.util.List<ProtocolDef> protocolDefs() {
        return java.util.List.of(
                new ProtocolDef("rest", "New REST Request", "New REST Request", "rest", this::openRestTab),
                new ProtocolDef("ws", "WebSocket", "New WebSocket", "ws", this::openWebSocketTab),
                new ProtocolDef("sse", "SSE Stream", "New SSE Stream", "topic", this::openSseTab),
                new ProtocolDef("graphql", "GraphQL", "New GraphQL Query", "rest", this::openGraphQLTab),
                new ProtocolDef("grpc", "gRPC", "New gRPC Client", "mcp", this::openGrpcTab),
                new ProtocolDef("sql", "SQL Client", "New SQL Client", "sql", this::openSqlTab),
                new ProtocolDef("mongo", "MongoDB Client", "New MongoDB Client", "mongo", this::openMongoTab),
                new ProtocolDef("s3", "S3 / Object Storage", "New S3 / Object Storage", "collection", this::openS3Tab),
                new ProtocolDef("azure", "Azure Blob", "New Azure Blob", "collection", this::openAzureTab),
                new ProtocolDef("gcs", "Google Cloud Storage", "New Google Cloud Storage", "collection", this::openGcsTab),
                new ProtocolDef("sftp", "SFTP", "New SFTP Browser", "server", this::openSftpTab),
                new ProtocolDef("ftp", "FTP", "New FTP Browser", "server", this::openFtpTab),
                new ProtocolDef("kafka", "Kafka", "New Kafka Client", "topic", this::openKafkaTab),
                new ProtocolDef("mqtt", "MQTT", "New MQTT Client", "topic", this::openMqttTab),
                new ProtocolDef("rabbitmq", "RabbitMQ", "New RabbitMQ Client", "topic", this::openRabbitMqTab),
                new ProtocolDef("redis", "Redis", "New Redis Client", "database", this::openRedisTab),
                new ProtocolDef("ldap", "LDAP / Active Directory", "New LDAP Browser", "server", this::openLdapTab),
                new ProtocolDef("snmp", "SNMP Browser", "New SNMP Browser", "server", this::openSnmpTab),
                new ProtocolDef("mcp", "MCP Inspector", "New MCP Inspector", "mcp", this::openMcpTab),
                new ProtocolDef("llm", "AI / LLM Tester", "New AI / LLM Tester", "ai", this::openLlmTab),
                new ProtocolDef("agent", "AI Agent (MCP tools)", "New AI Agent", "ai", this::openAgentTab));
    }

    /** Rebuilds the File menu + sidebar buttons from the enabled protocols. */
    private void rebuildProtocols() {
        java.util.List<ProtocolDef> enabled = protocolDefs().stream()
                .filter(d -> protocolPrefs.isEnabled(d.id())).toList();

        fileMenu.getItems().clear();
        for (ProtocolDef d : enabled) {
            MenuItem mi = new MenuItem(d.menuLabel(), Icons.of(d.icon(), 14));
            mi.setOnAction(e -> d.opener().run());
            fileMenu.getItems().add(mi);
        }
        fileMenu.getItems().addAll(new SeparatorMenuItem(), quitItem);

        protocolButtons.getChildren().clear();
        for (ProtocolDef d : enabled) {
            protocolButtons.getChildren().add(sidebarButton(d.label(), d.icon(), d.opener()));
        }
    }

    /** Lets the user choose which connection types are shown (persisted). */
    private void showProtocolsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner() != null) dialog.initOwner(owner());
        dialog.setTitle("Protocols");
        dialog.setHeaderText("Choose which connection types to show.\nUnchecked ones are hidden from the menu and sidebar.");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        VBox box = new VBox(6);
        box.setPadding(new Insets(12, 4, 4, 4));
        java.util.Map<String, CheckBox> checks = new java.util.LinkedHashMap<>();
        for (ProtocolDef d : protocolDefs()) {
            CheckBox cb = new CheckBox(d.label());
            cb.setSelected(protocolPrefs.isEnabled(d.id()));
            checks.put(d.id(), cb);
            box.getChildren().add(cb);
        }

        Button selectAll = new Button("Select all");
        selectAll.getStyleClass().add("btn-secondary");
        selectAll.setOnAction(e -> checks.values().forEach(cb -> cb.setSelected(true)));
        Button selectNone = new Button("Select none");
        selectNone.getStyleClass().add("btn-secondary");
        selectNone.setOnAction(e -> checks.values().forEach(cb -> cb.setSelected(false)));
        HBox toolbar = new HBox(8, selectAll, selectNone);
        toolbar.setPadding(new Insets(0, 4, 0, 4));

        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(340);
        VBox dialogContent = new VBox(8, toolbar, scroll);
        dialog.getDialogPane().setContent(dialogContent);
        dialog.setOnShown(ev -> {
            if (dialog.getDialogPane().getScene() != null) ThemeManager.get().register(dialog.getDialogPane().getScene());
        });

        if (dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
            java.util.Set<String> disabled = new java.util.LinkedHashSet<>();
            checks.forEach((id, cb) -> { if (!cb.isSelected()) disabled.add(id); });
            protocolPrefs.setDisabled(disabled);
            rebuildProtocols();
            log("Protocol visibility updated (" + (protocolDefs().size() - disabled.size()) + " enabled).");
        }
    }

    private void recordHistory(HistoryEntry entry) {
        try {
            historyStore.add(entry);
            if (historyPanel != null) historyPanel.onNewEntry();
        } catch (Exception e) {
            log("History save failed: " + e.getMessage());
        }
    }

    private void replayHistory(HistoryEntry entry) {
        RestClientView view = openRestTab();
        view.loadRequest(entry.detail());
        log("Replaying: " + entry.summary());
    }

    private void sendCurrent() {
        Tab tab = workspace.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getContent() instanceof RestClientView rest) {
            rest.sendRequest();
        }
    }

    private void toggleLog() {
        bottomPane.setExpanded(!bottomPane.isExpanded());
    }

    private void toggleTheme() {
        ThemeManager.Theme now = ThemeManager.get().toggle();
        log("Theme switched to " + now.label() + ".");
    }

    /** Menu label advertises the theme you'd switch *to*. */
    private String themeMenuLabel() {
        return "Switch to " + ThemeManager.get().current().other().label()
                + " Theme  (Ctrl+Shift+T)";
    }

    public void log(String message) {
        String line = LocalTime.now().format(TIME) + "  " + message + "\n";
        if (javafx.application.Platform.isFxApplicationThread()) logArea.appendText(line);
        else javafx.application.Platform.runLater(() -> logArea.appendText(line));
    }
}
