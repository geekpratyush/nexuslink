package com.nexuslink.ui.main;

import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.core.connection.ConnectionStore;
import com.nexuslink.core.history.HistoryEntry;
import com.nexuslink.core.history.HistoryStore;
import com.nexuslink.ui.connection.ConnectionsPanel;
import com.nexuslink.ui.graphql.GraphQLView;
import com.nexuslink.ui.help.HelpDialog;
import com.nexuslink.ui.icons.Icons;
import com.nexuslink.ui.kafka.KafkaView;
import com.nexuslink.ui.llm.LlmTesterView;
import com.nexuslink.ui.mcp.McpInspectorView;
import com.nexuslink.ui.mongo.MongoClientView;
import com.nexuslink.ui.rest.RestClientView;
import com.nexuslink.ui.s3.S3View;
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
    private final ConnectionStore connectionStore = new ConnectionStore();
    private ConnectionsPanel connectionsPanel;

    public Scene createScene() {
        initHistoryStore();
        root.getStyleClass().add("root");
        root.setTop(buildTopBar());
        root.setBottom(buildStatusBar());   // build status bar first — center init references it
        root.setCenter(buildCenter());
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
        Menu file = new Menu("File", Icons.of("file", 14));
        MenuItem newRest = new MenuItem("New REST Request", Icons.of("rest", 14));
        newRest.setOnAction(e -> openRestTab());
        MenuItem newWs = new MenuItem("New WebSocket", Icons.of("ws", 14));
        newWs.setOnAction(e -> openWebSocketTab());
        MenuItem newSse = new MenuItem("New SSE Stream", Icons.of("topic", 14));
        newSse.setOnAction(e -> openSseTab());
        MenuItem newGql = new MenuItem("New GraphQL Query", Icons.of("rest", 14));
        newGql.setOnAction(e -> openGraphQLTab());
        MenuItem newSql = new MenuItem("New SQL Client", Icons.of("sql", 14));
        newSql.setOnAction(e -> openSqlTab());
        MenuItem newMongo = new MenuItem("New MongoDB Client", Icons.of("mongo", 14));
        newMongo.setOnAction(e -> openMongoTab());
        MenuItem newS3 = new MenuItem("New S3 / Object Storage", Icons.of("collection", 14));
        newS3.setOnAction(e -> openS3Tab());
        MenuItem newKafka = new MenuItem("New Kafka Client", Icons.of("topic", 14));
        newKafka.setOnAction(e -> openKafkaTab());
        MenuItem newMcp = new MenuItem("New MCP Inspector", Icons.of("mcp", 14));
        newMcp.setOnAction(e -> openMcpTab());
        MenuItem newLlm = new MenuItem("New AI Agent / LLM Tester", Icons.of("ai", 14));
        newLlm.setOnAction(e -> openLlmTab());
        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(e -> javafx.application.Platform.exit());
        file.getItems().addAll(newRest, newWs, newSse, newGql, newSql, newMongo, newS3, newKafka, newMcp, newLlm, new SeparatorMenuItem(), quit);

        Menu ai = new Menu("AI", Icons.of("ai", 14));
        MenuItem mcpItem = new MenuItem("MCP Inspector", Icons.of("mcp", 14));
        mcpItem.setOnAction(e -> openMcpTab());
        MenuItem llmItem = new MenuItem("Agent / LLM Tester", Icons.of("ai", 14));
        llmItem.setOnAction(e -> openLlmTab());
        ai.getItems().addAll(mcpItem, llmItem);

        Menu view = new Menu("View", Icons.of("view", 14));
        MenuItem toggleLog = new MenuItem("Toggle Log Panel");
        toggleLog.setOnAction(e -> toggleLog());
        themeItem = new MenuItem(themeMenuLabel());
        themeItem.setOnAction(e -> toggleTheme());
        ThemeManager.get().addListener(() -> themeItem.setText(themeMenuLabel()));
        view.getItems().addAll(toggleLog, new SeparatorMenuItem(), themeItem);

        Menu tools = new Menu("Tools", Icons.of("tools", 14));
        MenuItem unlockVault = new MenuItem("Unlock Vault…");
        unlockVault.setOnAction(e -> { if (VaultSession.get().ensureUnlocked(owner())) updateVaultStatus(); });
        MenuItem lockVault = new MenuItem("Lock Vault");
        lockVault.setOnAction(e -> { VaultSession.get().lock(); log("Vault locked."); });
        tools.getItems().addAll(unlockVault, lockVault);

        Menu help = new Menu("Help", Icons.of("help", 14));
        MenuItem helpIndex = new MenuItem("Help Index  (F1)", Icons.of("help", 14));
        helpIndex.setOnAction(e -> HelpDialog.open("getting-started"));
        MenuItem shortcuts = new MenuItem("Keyboard Shortcuts");
        shortcuts.setOnAction(e -> HelpDialog.open("keyboard-shortcuts"));
        help.getItems().addAll(helpIndex, shortcuts);

        menuBar.getMenus().addAll(file, new Menu("Edit", Icons.of("edit", 14)), view,
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

        Button addBtn = sidebarButton("New REST Request", "rest", this::openRestTab);
        Button wsBtn = sidebarButton("WebSocket", "ws", this::openWebSocketTab);
        Button sseBtn = sidebarButton("SSE Stream", "topic", this::openSseTab);
        Button gqlBtn = sidebarButton("GraphQL", "rest", this::openGraphQLTab);
        Button sqlBtn = sidebarButton("SQL Client", "sql", this::openSqlTab);
        Button mongoBtn = sidebarButton("MongoDB Client", "mongo", this::openMongoTab);
        Button s3Btn = sidebarButton("S3 / Object Storage", "collection", this::openS3Tab);
        Button kafkaBtn = sidebarButton("Kafka", "topic", this::openKafkaTab);
        Button mcpBtn = sidebarButton("MCP Inspector", "mcp", this::openMcpTab);
        Button llmBtn = sidebarButton("AI Agent / LLM", "ai", this::openLlmTab);

        VBox buttons = new VBox(6, addBtn, wsBtn, sseBtn, gqlBtn, sqlBtn, mongoBtn, s3Btn, kafkaBtn, mcpBtn, llmBtn);
        VBox.setMargin(buttons, new Insets(8));

        VBox sidebar = new VBox(title, connectionsPanel, buttons);
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
        addTab("Agent " + (++newTabCounter), view);
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
            case SQL -> openSqlTab().prefill(d.target, d.username, d.authProps.get("password"));
            case MONGO -> openMongoTab().prefill(mongoTarget(d));
            case S3 -> openS3Tab().prefill(d.target, d.username, d.authProps.get("secretKey"));
            case KAFKA -> openKafkaTab().prefill(d.target);
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

    private Button sidebarButton(String label, String icon, Runnable action) {
        Button b = new Button(label, Icons.of(icon, 15));
        b.getStyleClass().add("btn-secondary");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setGraphicTextGap(8);
        b.setOnAction(e -> action.run());
        return b;
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
