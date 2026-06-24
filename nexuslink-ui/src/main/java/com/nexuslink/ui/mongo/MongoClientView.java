package com.nexuslink.ui.mongo;

import com.nexuslink.protocol.mongo.MongoQueryResult;
import com.nexuslink.protocol.mongo.MongoService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * MongoDB client tab — connect with a connection string, browse databases/collections,
 * and run find / aggregate / insert / update / delete operations using Extended-JSON.
 */
public final class MongoClientView extends BorderPane {

    private final MongoService service = new MongoService();

    private final TextField connField = new TextField("mongodb://localhost:27017");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final ComboBox<String> dbCombo = new ComboBox<>();
    private final ListView<String> collectionList = new ListView<>();

    private final ComboBox<String> modeCombo = new ComboBox<>();
    private final TextField limitField = new TextField("100");
    private final TextArea queryEditor = new TextArea();
    private final TextArea resultArea = new TextArea();
    private final Label resultStatus = new Label();

    private Consumer<String> logger = s -> {};

    public MongoClientView() {
        getStyleClass().add("mongo-view");
        setTop(buildConnectionBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    private VBox buildConnectionBar() {
        connField.getStyleClass().add("nl-field");
        HBox.setHgrow(connField, Priority.ALWAYS);
        connField.setPromptText("mongodb://host:27017  or  mongodb+srv://user:pass@cluster/…");

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("databases"));

        Label lbl = new Label("Connection:");
        lbl.getStyleClass().add("meta-label");
        HBox row = new HBox(8, lbl, connField, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        // Left: database picker + collection list
        Label dbLbl = new Label("DATABASE");
        dbLbl.getStyleClass().add("sidebar-title");
        dbCombo.setMaxWidth(Double.MAX_VALUE);
        dbCombo.valueProperty().addListener((o, ov, db) -> { if (db != null) selectDatabase(db); });

        Label collLbl = new Label("COLLECTIONS");
        collLbl.getStyleClass().add("sidebar-title");
        collectionList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && collectionList.getSelectionModel().getSelectedItem() != null) {
                modeCombo.setValue("find");
                queryEditor.setText("{}");
                run();
            }
        });
        VBox left = new VBox(dbLbl, dbCombo, collLbl, collectionList);
        left.setPadding(new Insets(6));
        VBox.setVgrow(collectionList, Priority.ALWAYS);
        left.setMinWidth(200);

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

        SplitPane sp = new SplitPane(left, right);
        sp.setDividerPositions(0.24);
        return sp;
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
            dbCombo.setItems(FXCollections.observableArrayList(dbs));
            if (!dbs.isEmpty()) dbCombo.setValue(dbs.contains("test") ? "test" : dbs.get(0));
            logger.accept("Mongo connected — " + dbs.size() + " dbs");
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

    private void selectDatabase(String db) {
        service.useDatabase(db);
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() { return service.listCollectionNames(); }
        };
        task.setOnSucceeded(e -> collectionList.setItems(FXCollections.observableArrayList(task.getValue())));
        runBg(task);
    }

    private void run() {
        String collection = collectionList.getSelectionModel().getSelectedItem();
        if (collection == null) { resultStatus.setText("Select a collection first"); return; }
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
            refreshCollectionsSoon();
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

    private void refreshCollectionsSoon() {
        String db = dbCombo.getValue();
        if (db != null) selectDatabase(db);
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
