package com.nexuslink.ui.main;

import com.nexuslink.core.history.HistoryEntry;
import com.nexuslink.core.history.HistoryStore;
import com.nexuslink.ui.help.HelpDialog;
import com.nexuslink.ui.llm.LlmTesterView;
import com.nexuslink.ui.mcp.McpInspectorView;
import com.nexuslink.ui.mongo.MongoClientView;
import com.nexuslink.ui.rest.RestClientView;
import com.nexuslink.ui.sql.SqlClientView;
import com.nexuslink.ui.theme.ThemeManager;
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
    private int newTabCounter = 0;

    private HistoryStore historyStore;
    private HistoryPanel historyPanel;
    private TabPane bottomTabs;
    private MenuItem themeItem;

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
        Menu file = new Menu("File");
        MenuItem newRest = new MenuItem("New REST Request");
        newRest.setOnAction(e -> openRestTab());
        MenuItem newWs = new MenuItem("New WebSocket");
        newWs.setOnAction(e -> openWebSocketTab());
        MenuItem newSql = new MenuItem("New SQL Client");
        newSql.setOnAction(e -> openSqlTab());
        MenuItem newMongo = new MenuItem("New MongoDB Client");
        newMongo.setOnAction(e -> openMongoTab());
        MenuItem newMcp = new MenuItem("New MCP Inspector");
        newMcp.setOnAction(e -> openMcpTab());
        MenuItem newLlm = new MenuItem("New AI Agent / LLM Tester");
        newLlm.setOnAction(e -> openLlmTab());
        MenuItem quit = new MenuItem("Quit");
        quit.setOnAction(e -> javafx.application.Platform.exit());
        file.getItems().addAll(newRest, newWs, newSql, newMongo, newMcp, newLlm, new SeparatorMenuItem(), quit);

        Menu ai = new Menu("AI");
        MenuItem mcpItem = new MenuItem("MCP Inspector");
        mcpItem.setOnAction(e -> openMcpTab());
        MenuItem llmItem = new MenuItem("Agent / LLM Tester");
        llmItem.setOnAction(e -> openLlmTab());
        ai.getItems().addAll(mcpItem, llmItem);

        Menu view = new Menu("View");
        MenuItem toggleLog = new MenuItem("Toggle Log Panel");
        toggleLog.setOnAction(e -> toggleLog());
        themeItem = new MenuItem(themeMenuLabel());
        themeItem.setOnAction(e -> toggleTheme());
        ThemeManager.get().addListener(() -> themeItem.setText(themeMenuLabel()));
        view.getItems().addAll(toggleLog, new SeparatorMenuItem(), themeItem);

        Menu help = new Menu("Help");
        MenuItem helpIndex = new MenuItem("Help Index  (F1)");
        helpIndex.setOnAction(e -> HelpDialog.open("getting-started"));
        MenuItem shortcuts = new MenuItem("Keyboard Shortcuts");
        shortcuts.setOnAction(e -> HelpDialog.open("keyboard-shortcuts"));
        help.getItems().addAll(helpIndex, shortcuts);

        menuBar.getMenus().addAll(file, new Menu("Edit"), view,
                new Menu("Connection"), new Menu("Tools"), ai, help);
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

        TreeItem<String> rootItem = new TreeItem<>("Workspace");
        rootItem.setExpanded(true);
        TreeItem<String> prod = new TreeItem<>("📁 Production");
        TreeItem<String> dev = new TreeItem<>("📁 Development");
        dev.setExpanded(true);
        dev.getChildren().addAll(
                new TreeItem<>("🔗 httpbin (REST)"),
                new TreeItem<>("🔗 JSONPlaceholder (REST)"));
        rootItem.getChildren().addAll(prod, dev);

        TreeView<String> tree = new TreeView<>(rootItem);
        tree.getStyleClass().add("connection-tree");
        tree.setShowRoot(false);
        VBox.setVgrow(tree, Priority.ALWAYS);

        // Double-click a REST leaf opens a tab
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<String> sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue().contains("REST")) openRestTab();
            }
        });

        Button addBtn = new Button("+ New REST Request");
        addBtn.getStyleClass().add("btn-secondary");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> openRestTab());

        Button wsBtn = new Button("+ WebSocket");
        wsBtn.getStyleClass().add("btn-secondary");
        wsBtn.setMaxWidth(Double.MAX_VALUE);
        wsBtn.setOnAction(e -> openWebSocketTab());

        Button sqlBtn = new Button("+ SQL Client");
        sqlBtn.getStyleClass().add("btn-secondary");
        sqlBtn.setMaxWidth(Double.MAX_VALUE);
        sqlBtn.setOnAction(e -> openSqlTab());

        Button mongoBtn = new Button("+ MongoDB Client");
        mongoBtn.getStyleClass().add("btn-secondary");
        mongoBtn.setMaxWidth(Double.MAX_VALUE);
        mongoBtn.setOnAction(e -> openMongoTab());

        Button mcpBtn = new Button("+ MCP Inspector");
        mcpBtn.getStyleClass().add("btn-secondary");
        mcpBtn.setMaxWidth(Double.MAX_VALUE);
        mcpBtn.setOnAction(e -> openMcpTab());

        Button llmBtn = new Button("+ AI Agent / LLM");
        llmBtn.getStyleClass().add("btn-secondary");
        llmBtn.setMaxWidth(Double.MAX_VALUE);
        llmBtn.setOnAction(e -> openLlmTab());

        VBox buttons = new VBox(6, addBtn, wsBtn, sqlBtn, mongoBtn, mcpBtn, llmBtn);
        VBox.setMargin(buttons, new Insets(8));

        VBox sidebar = new VBox(title, tree, buttons);
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
        Label version = new Label("v1.0.0-SNAPSHOT  ·  RouteForge");
        HBox bar = new HBox(statusConnections, spacer, version);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return new BorderPane(bar);
    }

    // ---- Actions ----

    private RestClientView openRestTab() {
        RestClientView view = new RestClientView();
        view.setLogger(this::log);
        view.setHistoryRecorder(this::recordHistory);
        Tab tab = new Tab("REST " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
        return view;
    }

    private void openWebSocketTab() {
        WebSocketView view = new WebSocketView();
        view.setLogger(this::log);
        Tab tab = new Tab("WS " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
    }

    private void openSqlTab() {
        SqlClientView view = new SqlClientView();
        view.setLogger(this::log);
        Tab tab = new Tab("SQL " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
    }

    private void openMongoTab() {
        MongoClientView view = new MongoClientView();
        view.setLogger(this::log);
        Tab tab = new Tab("Mongo " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
    }

    private void openMcpTab() {
        McpInspectorView view = new McpInspectorView();
        view.setLogger(this::log);
        Tab tab = new Tab("MCP " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
    }

    private void openLlmTab() {
        LlmTesterView view = new LlmTesterView();
        view.setLogger(this::log);
        Tab tab = new Tab("Agent " + (++newTabCounter), view);
        tab.setClosable(true);
        workspace.getTabs().add(tab);
        workspace.getSelectionModel().select(tab);
        statusConnections.setText(workspace.getTabs().size() + " open tab(s)");
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
