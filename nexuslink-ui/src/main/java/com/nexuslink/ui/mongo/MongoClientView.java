package com.nexuslink.ui.mongo;

import com.nexuslink.core.connection.AuthMethod;
import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.protocol.mongo.MongoExplorer;
import com.nexuslink.protocol.mongo.MongoQueryResult;
import com.nexuslink.protocol.mongo.MongoService;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * MongoDB client tab — connect with a connection string, then browse the live object tree
 * (databases → collections → indexes, with collStats in the details panel) and run
 * find / aggregate / insert / update / delete operations using Extended-JSON.
 */
public final class MongoClientView extends BorderPane {

    private final MongoService service = new MongoService();
    private final MongoExplorer explorerModel = new MongoExplorer(service);
    private final ResourceExplorerView explorer = new ResourceExplorerView("Databases");

    private final TextField connField = new TextField("mongodb://localhost:27017");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final ComboBox<String> modeCombo = new ComboBox<>();
    private final TextField limitField = new TextField("100");
    private final TextArea queryEditor = new TextArea();
    private final TextArea resultArea = new TextArea();
    private final Label resultStatus = new Label();

    private String activeDb;
    private String activeCollection;

    private Consumer<String> logger = s -> {};
    private Consumer<ConnectionProfile> onSave = p -> {};

    public MongoClientView() {
        getStyleClass().add("mongo-view");
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

    /** Pre-fills the connection string (used when opening a saved/sample connection). */
    public void prefill(String connectionString) {
        if (connectionString != null && !connectionString.isBlank()) connField.setText(connectionString);
    }

    private void createCollectionDialog() {
        if (activeDb == null) { statusLabel.setText("Select a database in the tree first"); return; }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create collection");
        dialog.setHeaderText("New collection in database '" + activeDb + "'");
        dialog.setContentText("Name:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            runStructure(() -> { service.useDatabase(activeDb); service.createCollection(name.trim()); return "Created collection " + name.trim(); });
        });
    }

    private void createIndexDialog() {
        if (activeCollection == null) { statusLabel.setText("Select a collection in the tree first"); return; }
        Dialog<ButtonType> d = new Dialog<>();
        if (getScene() != null) d.initOwner(getScene().getWindow());
        d.setTitle("Create index");
        d.setHeaderText("New index on '" + activeCollection + "'");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setOnShown(ev -> { if (d.getDialogPane().getScene() != null) com.nexuslink.ui.theme.ThemeManager.get().register(d.getDialogPane().getScene()); });
        TextField keys = new TextField("{ \"field\": 1 }");
        CheckBox unique = new CheckBox("Unique");
        GridPane g = new GridPane();
        g.setHgap(10); g.setVgap(8); g.setPadding(new Insets(12, 4, 4, 4));
        g.addRow(0, new Label("Keys (JSON):"), keys);
        g.add(unique, 1, 1);
        d.getDialogPane().setContent(g);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (keys.getText().isBlank()) return;
            runStructure(() -> {
                if (activeDb != null) service.useDatabase(activeDb);
                return "Created index " + service.createIndex(activeCollection, keys.getText().trim(), unique.isSelected());
            });
        });
    }

    private void runStructure(java.util.concurrent.Callable<String> action) {
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Working…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return action.call(); }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText(task.getValue());
            logger.accept("Mongo: " + task.getValue());
            explorer.load();
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + task.getException().getMessage());
        });
        runBg(task);
    }

    private void saveCurrent() {
        String conn = connField.getText().trim();
        if (conn.isEmpty()) { statusLabel.setText("Enter a connection string before saving"); return; }
        TextInputDialog dialog = new TextInputDialog(conn);
        dialog.setTitle("Save connection");
        dialog.setHeaderText("Save this MongoDB connection for later");
        dialog.setContentText("Name:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            ConnectionProfile p = new ConnectionProfile(name.trim(), ConnectionProfile.Protocol.MONGO, conn)
                    .withAuth(AuthMethod.CONNECTION_STRING);
            onSave.accept(p);
            logger.accept("Saved MongoDB connection: " + name.trim());
        });
    }

    private VBox buildConnectionBar() {
        connField.getStyleClass().add("nl-field");
        HBox.setHgrow(connField, Priority.ALWAYS);
        connField.setPromptText("mongodb://host:27017  or  mongodb+srv://user:pass@cluster/…");

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setOnAction(e -> saveCurrent());

        MenuButton structureBtn = new MenuButton("Structure");
        structureBtn.getStyleClass().add("btn-secondary");
        MenuItem createColl = new MenuItem("Create Collection…");
        createColl.setOnAction(e -> createCollectionDialog());
        MenuItem createIndex = new MenuItem("Create Index…");
        createIndex.setOnAction(e -> createIndexDialog());
        structureBtn.getItems().addAll(createColl, createIndex);

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label lbl = new Label("Connection:");
        lbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, lbl, connField, connectBtn, saveBtn, structureBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        // Left: live object explorer (databases → collections → indexes)
        explorer.setMinWidth(240);
        explorer.setOnSelect(this::onExplorerSelect);
        explorer.setOnActivate(node -> {
            if (node.kind() == ResourceNode.Kind.COLLECTION) {
                onExplorerSelect(node);
                modeCombo.setValue("find");
                queryEditor.setText("{}");
                run();
            }
        });

        // Right: query editor + result
        modeCombo.getItems().addAll("find", "aggregate", "insertOne", "updateMany", "deleteMany");
        modeCombo.setValue("find");
        modeCombo.valueProperty().addListener((o, ov, m) -> updateEditorHint(m));

        limitField.setPrefWidth(70);
        Label limitLbl = new Label("limit:");
        limitLbl.getStyleClass().add("meta-label");

        Button runBtn = new Button("Run  (Ctrl+Enter)");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> run());

        HBox controls = new HBox(8, new Label("Operation:"), modeCombo, limitLbl, limitField, runBtn, resultStatus);
        ((Label) controls.getChildren().get(0)).getStyleClass().add("meta-label");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6));
        resultStatus.getStyleClass().add("meta-label");

        queryEditor.getStyleClass().add("code-area");
        queryEditor.setPrefRowCount(5);
        queryEditor.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) run();
        });
        updateEditorHint("find");

        resultArea.getStyleClass().add("code-area");
        resultArea.setEditable(false);
        resultArea.setPromptText("Documents appear here…");

        VBox right = new VBox(6, controls, queryEditor, resultArea);
        right.setPadding(new Insets(8));
        VBox.setVgrow(resultArea, Priority.ALWAYS);

        SplitPane sp = new SplitPane(explorer, right);
        sp.setDividerPositions(0.28);
        return sp;
    }

    private void onExplorerSelect(ResourceNode node) {
        switch (node.kind()) {
            case DATABASE -> {
                activeDb = node.label();
                activeCollection = null;
                service.useDatabase(activeDb);
            }
            case COLLECTION -> {
                String path = node.id().substring("coll:".length());   // db.collection
                int dot = path.indexOf('.');
                activeDb = path.substring(0, dot);
                activeCollection = path.substring(dot + 1);
                service.useDatabase(activeDb);
            }
            default -> { /* index / other: keep current active collection */ }
        }
    }

    private void updateEditorHint(String mode) {
        queryEditor.setPromptText(switch (mode) {
            case "find" -> "{ }  — filter, e.g. { \"role\": \"developer\" }";
            case "aggregate" -> "[ { \"$group\": { \"_id\": \"$role\", \"n\": { \"$sum\": 1 } } } ]";
            case "insertOne" -> "{ \"name\": \"Alice\", \"role\": \"admin\" }  — document to insert";
            case "updateMany" -> "filter ||| update    e.g.  { \"name\":\"Alice\" } ||| { \"$set\": { \"role\":\"x\" } }";
            case "deleteMany" -> "{ }  — filter for documents to delete";
            default -> "";
        });
        if (queryEditor.getText().isBlank()) {
            queryEditor.setText(mode.equals("aggregate") ? "[]" : "{}");
        }
    }

    private void connect() {
        connectBtn.setDisable(true);
        statusLabel.setText("Connecting…");
        String conn = connField.getText().trim();
        logger.accept("Mongo connect → " + conn.replaceAll(":[^:@/]+@", ":***@"));
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return service.connect(conn); }
        };
        task.setOnSucceeded(e -> {
            List<String> dbs = task.getValue();
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + dbs.size() + " database(s)");
            logger.accept("Mongo connected — " + dbs.size() + " dbs");
            explorer.setExplorer(explorerModel);
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("Mongo connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task);
    }

    private void run() {
        if (activeCollection == null) { resultStatus.setText("Select a collection in the tree first"); return; }
        if (activeDb != null) service.useDatabase(activeDb);
        String collection = activeCollection;
        String mode = modeCombo.getValue();
        String body = queryEditor.getText().trim();
        int limit = parseLimit();
        resultStatus.setText("Running " + mode + "…");
        logger.accept("Mongo " + mode + " → " + collection);

        Task<String> task = new Task<>() {
            @Override protected String call() {
                return switch (mode) {
                    case "find" -> renderDocs(service.find(collection, body, limit));
                    case "aggregate" -> renderDocs(service.aggregate(collection, body));
                    case "insertOne" -> "Inserted _id: " + service.insertOne(collection, body);
                    case "updateMany" -> {
                        String[] parts = body.split("\\|\\|\\|", 2);
                        if (parts.length != 2) yield "updateMany needs:  filter ||| update";
                        yield service.updateMany(collection, parts[0].trim(), parts[1].trim()) + " document(s) modified";
                    }
                    case "deleteMany" -> service.deleteMany(collection, body) + " document(s) deleted";
                    default -> "";
                };
            }
        };
        task.setOnSucceeded(e -> {
            resultArea.setText(task.getValue());
            resultStatus.getStyleClass().setAll("meta-label");
            resultStatus.setText("ok");
        });
        task.setOnFailed(e -> {
            resultStatus.getStyleClass().setAll("status-err");
            resultStatus.setText("✖ " + task.getException().getMessage());
            resultArea.setText("Error: " + task.getException().getMessage());
        });
        runBg(task);
    }

    private String renderDocs(MongoQueryResult r) {
        if (!r.success()) throw new RuntimeException(r.error());
        Platform.runLater(() -> resultStatus.setText(r.count() + " doc(s) · " + r.durationMs() + " ms"));
        return r.documents().isEmpty() ? "(no documents)" : String.join("\n", r.documents());
    }

    private int parseLimit() {
        try { return Math.max(1, Integer.parseInt(limitField.getText().trim())); }
        catch (NumberFormatException e) { return 100; }
    }

    private void runBg(Task<?> task) {
        Thread t = new Thread(task, "mongo-task");
        t.setDaemon(true);
        t.start();
    }
}
