package com.nexuslink.ui.help;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The NexusLink Help Dialog — opened via F1, Help menu, or any context ? button.
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │  [x]  🔍 Search help...                    NexusLink Help  │
 *   ├──────────────┬──────────────────────────┬───────────────────┤
 *   │  Topic Index │  Rendered Content         │  On This Page     │
 *   │  (tree)      │  (markdown rendered)      │  (section nav)    │
 *   │              │                           │                   │
 *   ├──────────────┴──────────────────────────┴───────────────────┤
 *   │  💡 Did you know: ...tip text...                            │
 *   └─────────────────────────────────────────────────────────────┘
 */
public final class HelpDialog {

    private static HelpDialog instance;

    private final Stage stage;
    private final HelpService helpService = HelpService.get();

    private TextField searchField;
    private TreeView<HelpNavItem> topicTree;
    private ScrollPane contentScroll;
    private VBox contentPane;
    private VBox sectionNav;
    private com.nexuslink.ui.markdown.MarkdownView markdownView;
    private Label tipLabel;

    private final StringProperty currentTopicId = new SimpleStringProperty();
    private Timer searchDebounce;

    private static final String[] TIPS = {
        "Press F1 on any field to get context-sensitive help.",
        "Use Ctrl+K to open the command palette for quick navigation.",
        "Right-click a response to copy as cURL, code, or raw.",
        "Environment variables like ${BASE_URL} work in every URL field.",
        "Star any request in history to pin it as a favorite.",
        "Double-click a tab to detach it into its own window.",
        "Consumer lag is cached — hit Refresh to force a real-time update.",
        "The certificate manager warns you 30 days before any cert expires.",
        "You can drag .proto files directly onto the gRPC connection panel.",
        "Use Ctrl+Shift+F to search across all connections, history, and schemas."
    };

    private HelpDialog() {
        stage = new Stage(StageStyle.DECORATED);
        stage.initModality(Modality.NONE); // non-blocking — users can work while reading
        stage.setTitle("NexusLink Help");
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setWidth(1100);
        stage.setHeight(700);

        Scene scene = new Scene(buildLayout());
        com.nexuslink.ui.theme.ThemeManager.get().register(scene,
                "/com/nexuslink/ui/css/help-dialog.css");
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (searchField.getText().isBlank()) close();
                else searchField.clear();
            }
        });
        stage.setScene(scene);
    }

    /** Open the dialog, optionally navigating to a specific topic#anchor. */
    public static void open(String navigationTarget) {
        if (instance == null) instance = new HelpDialog();
        instance.show(navigationTarget);
    }

    /** Open the dialog and immediately run a search query (deep-link into search). */
    public static void openWithSearch(String query) {
        if (instance == null) instance = new HelpDialog();
        instance.show(null);
        Platform.runLater(() -> {
            instance.searchField.setText(query);
            instance.searchField.positionCaret(query.length());
        });
    }

    /** Open on the topic most relevant to the currently focused UI component. */
    public static void openContextual(String componentId) {
        String target = HelpService.get()
                .contextTarget(componentId)
                .orElse("getting-started");
        open(target);
    }

    private void show(String navigationTarget) {
        stage.show();
        stage.toFront();
        Platform.runLater(() -> {
            searchField.clear();
            searchField.requestFocus();
            if (navigationTarget != null && !navigationTarget.isBlank()) {
                navigateTo(navigationTarget);
            }
            rotateTip();
        });
    }

    private void close() {
        FadeTransition fade = new FadeTransition(Duration.millis(150), stage.getScene().getRoot());
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> stage.hide());
        fade.play();
    }

    // ---- Layout ----

    private BorderPane buildLayout() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("help-dialog-root");

        root.setTop(buildHeader());
        root.setCenter(buildBody());
        root.setBottom(buildTipBar());

        return root;
    }

    private HBox buildHeader() {
        HBox header = new HBox(12);
        header.getStyleClass().add("help-header");
        header.setPadding(new Insets(12, 16, 12, 16));

        Label icon = new Label("?");
        icon.getStyleClass().add("help-icon");

        searchField = new TextField();
        searchField.setPromptText("Search help... (try \"oauth\", \"kafka consumer\", \"cert expired\")");
        searchField.getStyleClass().add("help-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, old, query) -> scheduleSearch(query));
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) topicTree.requestFocus();
        });

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("help-close-btn");
        closeBtn.setOnAction(e -> close());

        header.getChildren().addAll(icon, searchField, closeBtn);
        return header;
    }

    private SplitPane buildBody() {
        SplitPane split = new SplitPane();
        split.setOrientation(Orientation.HORIZONTAL);
        split.getStyleClass().add("help-body");

        // Left: topic index tree
        topicTree = buildTopicTree();
        VBox leftPane = new VBox(topicTree);
        leftPane.getStyleClass().add("help-left-pane");
        VBox.setVgrow(topicTree, Priority.ALWAYS);
        leftPane.setMinWidth(200);
        leftPane.setPrefWidth(220);

        // Center: rendered content
        contentPane = new VBox();
        contentPane.getStyleClass().add("help-content");
        contentPane.setPadding(new Insets(20, 24, 20, 24));
        contentScroll = new ScrollPane(contentPane);
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("help-content-scroll");

        // WebView markdown renderer for topic content (search results use contentScroll's cards)
        markdownView = new com.nexuslink.ui.markdown.MarkdownView();
        javafx.scene.layout.StackPane center = new javafx.scene.layout.StackPane(contentScroll, markdownView);

        // Right: on-this-page section nav
        sectionNav = new VBox(8);
        sectionNav.getStyleClass().add("help-section-nav");
        sectionNav.setPadding(new Insets(16, 12, 16, 12));
        Label navTitle = new Label("On This Page");
        navTitle.getStyleClass().add("help-section-nav-title");
        sectionNav.getChildren().add(navTitle);
        ScrollPane navScroll = new ScrollPane(sectionNav);
        navScroll.setFitToWidth(true);
        navScroll.getStyleClass().add("help-nav-scroll");
        navScroll.setMinWidth(160);
        navScroll.setPrefWidth(180);

        split.getItems().addAll(leftPane, center, navScroll);
        split.setDividerPositions(0.2, 0.78);

        return split;
    }

    private TreeView<HelpNavItem> buildTopicTree() {
        TreeItem<HelpNavItem> root = new TreeItem<>();
        root.setExpanded(true);

        // Group topics by category
        Map<String, List<HelpTopic>> byCategory = new LinkedHashMap<>();
        for (HelpTopic topic : helpService.allTopics()) {
            byCategory.computeIfAbsent(topic.category(), k -> new ArrayList<>()).add(topic);
        }

        // Recent topics node
        TreeItem<HelpNavItem> recentNode = new TreeItem<>(new HelpNavItem("Recent", null, true));
        recentNode.setExpanded(true);
        updateRecentNode(recentNode);
        root.getChildren().add(recentNode);

        // Category nodes
        byCategory.forEach((category, topics) -> {
            TreeItem<HelpNavItem> catNode = new TreeItem<>(new HelpNavItem(category, null, true));
            catNode.setExpanded(true);
            topics.forEach(t -> catNode.getChildren().add(
                    new TreeItem<>(new HelpNavItem(t.title(), t.id(), false))));
            root.getChildren().add(catNode);
        });

        TreeView<HelpNavItem> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.getStyleClass().add("help-topic-tree");
        tree.setCellFactory(tv -> new HelpTopicCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null && item.getValue().topicId() != null) {
                navigateTo(item.getValue().topicId());
            }
        });
        return tree;
    }

    private HBox buildTipBar() {
        HBox bar = new HBox(8);
        bar.getStyleClass().add("help-tip-bar");
        bar.setPadding(new Insets(8, 16, 8, 16));

        Label bulb = new Label("💡");
        tipLabel = new Label();
        tipLabel.getStyleClass().add("help-tip-text");
        HBox.setHgrow(tipLabel, Priority.ALWAYS);

        bar.getChildren().addAll(bulb, tipLabel);
        return bar;
    }

    // ---- Navigation & Search ----

    private void navigateTo(String target) {
        String[] parts = target.split("#", 2);
        String topicId = parts[0];
        String anchor  = parts.length > 1 ? parts[1] : null;

        helpService.topic(topicId).ifPresentOrElse(topic -> {
            currentTopicId.set(topicId);
            helpService.recordViewed(topicId);
            String markdown = helpService.loadContent(topicId);
            renderMarkdown(markdown, topic);
            if (anchor != null) scrollToAnchor(anchor);
        }, () -> renderError("Topic not found: " + topicId));
    }

    private void scheduleSearch(String query) {
        if (searchDebounce != null) searchDebounce.cancel();
        if (query.isBlank()) {
            showTopicIndex();
            return;
        }
        searchDebounce = new Timer(true);
        searchDebounce.schedule(new TimerTask() {
            @Override public void run() {
                List<SearchResult> results = helpService.search(query);
                Platform.runLater(() -> showSearchResults(results, query));
            }
        }, 150);
    }

    private void showSearchResults(List<SearchResult> results, String query) {
        showSearchView();
        contentPane.getChildren().clear();
        sectionNav.getChildren().clear();

        Label heading = new Label("Search results for: " + query);
        heading.getStyleClass().add("help-h1");
        contentPane.getChildren().add(heading);

        if (results.isEmpty()) {
            Label none = new Label("No results found. Try different keywords or browse the index.");
            none.getStyleClass().add("help-body-text");
            contentPane.getChildren().add(none);

            Label suggest = new Label("Suggestions:");
            suggest.getStyleClass().add("help-h3");
            contentPane.getChildren().add(suggest);
            contentPane.getChildren().add(new Label("• Check spelling"));
            contentPane.getChildren().add(new Label("• Try broader terms (e.g. \"auth\" instead of \"authentication\")"));
            contentPane.getChildren().add(new Label("• Browse the index on the left"));
            return;
        }

        for (SearchResult result : results) {
            VBox card = buildSearchResultCard(result);
            contentPane.getChildren().add(card);
        }
    }

    private VBox buildSearchResultCard(SearchResult result) {
        VBox card = new VBox(4);
        card.getStyleClass().add("help-search-result-card");
        card.setPadding(new Insets(12));
        card.setOnMouseClicked(e -> navigateTo(result.navigationTarget()));

        Label title = new Label(result.topic().title()
                + (result.sectionTitle() != null ? " › " + result.sectionTitle() : ""));
        title.getStyleClass().add("help-result-title");

        Label category = new Label(result.topic().category());
        category.getStyleClass().add("help-result-category");

        HBox meta = new HBox(8, category);

        card.getChildren().addAll(title, meta);

        if (result.excerpt() != null && !result.excerpt().isBlank()) {
            TextFlow excerpt = buildHighlightedText(result.excerpt());
            excerpt.getStyleClass().add("help-result-excerpt");
            card.getChildren().add(excerpt);
        }

        return card;
    }

    private void showTopicIndex() {
        markdownView.setMarkdown("""
            # NexusLink Help

            Search above or pick a topic from the index on the left.

            NexusLink is a **universal protocol workbench** — REST, WebSocket, SQL, MongoDB,
            MCP, LLMs and more in one place. Markdown and Mermaid diagrams render right here.

            ```mermaid
            flowchart LR
              A[Pick a connection] --> B[Connect]
              B --> C[Browse objects]
              B --> D[Send / query]
              C --> E[Inspect details]
            ```
            """);
        showContentView();
        sectionNav.getChildren().clear();
    }

    // ---- Markdown Renderer ----

    private void renderMarkdown(String markdown, HelpTopic topic) {
        markdownView.setMarkdown(markdown);
        showContentView();

        // "On this page" nav built from headings; clicking scrolls the rendered page.
        sectionNav.getChildren().clear();
        Label navTitle = new Label("On This Page");
        navTitle.getStyleClass().add("help-section-nav-title");
        sectionNav.getChildren().add(navTitle);
        boolean inFence = false;
        for (String line : markdown.split("\n")) {
            if (line.startsWith("```")) { inFence = !inFence; continue; }
            if (inFence) continue;
            int level = line.startsWith("## ") ? 2 : line.startsWith("# ") ? 1 : 0;
            if (level == 0) continue;
            String text = line.substring(level + 1).trim();
            Hyperlink navLink = new Hyperlink(text);
            navLink.getStyleClass().add("help-nav-link-" + level);
            navLink.setOnAction(e -> markdownView.scrollToHeading(text));
            sectionNav.getChildren().add(navLink);
        }
    }

    /** Show the WebView (topic content); hide the search-results pane. */
    private void showContentView() {
        markdownView.setVisible(true);
        contentScroll.setVisible(false);
    }

    /** Show the search-results pane (cards); hide the WebView. */
    private void showSearchView() {
        markdownView.setVisible(false);
        contentScroll.setVisible(true);
    }

    private void addHeading(String text, int level, VBox parent) {
        Label label = new Label(text);
        label.getStyleClass().add("help-h" + level);
        label.setWrapText(true);
        parent.getChildren().add(label);

        if (level <= 2) {
            String anchor = text.toLowerCase().replaceAll("[^a-z0-9]+", "-");
            label.setId("anchor-" + anchor);

            Hyperlink navLink = new Hyperlink(text);
            navLink.getStyleClass().add("help-nav-link-" + level);
            navLink.setOnAction(e -> scrollToAnchor(anchor));
            sectionNav.getChildren().add(navLink);
        }
    }

    private void flushParagraph(StringBuilder sb, VBox parent) {
        String text = sb.toString().trim();
        if (!text.isEmpty()) {
            Label para = new Label(text);
            para.getStyleClass().add("help-body-text");
            para.setWrapText(true);
            parent.getChildren().add(para);
        }
        sb.setLength(0);
    }

    private void scrollToAnchor(String anchor) {
        Platform.runLater(() -> {
            Node target = contentPane.lookup("#anchor-" + anchor);
            if (target != null) {
                double y = target.getBoundsInParent().getMinY();
                double height = contentPane.getBoundsInParent().getHeight();
                contentScroll.setVvalue(y / height);
            }
        });
    }

    private TextFlow buildHighlightedText(String text) {
        TextFlow flow = new TextFlow();
        String[] parts = text.split("<<|>>");
        for (int i = 0; i < parts.length; i++) {
            Text t = new Text(parts[i]);
            if (i % 2 == 1) t.getStyleClass().add("help-highlight");
            flow.getChildren().add(t);
        }
        return flow;
    }

    private void renderError(String message) {
        markdownView.setMarkdown("# ⚠ Not found\n\n" + message);
        showContentView();
        sectionNav.getChildren().clear();
    }

    private void rotateTip() {
        String tip = TIPS[new Random().nextInt(TIPS.length)];
        FadeTransition fade = new FadeTransition(Duration.millis(300), tipLabel);
        fade.setFromValue(0);
        fade.setToValue(1);
        tipLabel.setText(tip);
        fade.play();
    }

    private void updateRecentNode(TreeItem<HelpNavItem> recentNode) {
        recentNode.getChildren().clear();
        helpService.recentTopics().forEach(id ->
                helpService.topic(id).ifPresent(t ->
                        recentNode.getChildren().add(
                                new TreeItem<>(new HelpNavItem(t.title(), id, false)))));
    }

    // ---- Inner types ----

    record HelpNavItem(String label, String topicId, boolean isCategory) {
        @Override public String toString() { return label; }
    }

    private static final class HelpTopicCell extends TreeCell<HelpNavItem> {
        @Override
        protected void updateItem(HelpNavItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (item.isCategory()) {
                setText(item.label());
                getStyleClass().add("help-tree-category");
            } else {
                setText(item.label());
                getStyleClass().remove("help-tree-category");
            }
        }
    }
}
