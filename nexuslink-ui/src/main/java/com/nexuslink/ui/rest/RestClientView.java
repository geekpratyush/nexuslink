package com.nexuslink.ui.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexuslink.core.history.HistoryEntry;
import com.nexuslink.protocol.http.rest.RestExecutionService;
import com.nexuslink.protocol.http.rest.RestRequest;
import com.nexuslink.protocol.http.rest.RestResponse;
import com.nexuslink.ui.help.HelpDialog;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The REST client tab content — method bar, request editor tabs, and response panel.
 * Executes off the UI thread via {@link Task}; never blocks JavaFX.
 */
public final class RestClientView extends BorderPane {

    private final RestExecutionService executor = new RestExecutionService();
    private final ObjectMapper json = new ObjectMapper();
    private final RestRequest request = new RestRequest();

    private final ObservableList<RestRequest.KeyValue> paramRows = FXCollections.observableArrayList();
    private final ObservableList<RestRequest.KeyValue> headerRows = FXCollections.observableArrayList();

    private ComboBox<String> methodCombo;
    private TextField urlField;
    private Button sendButton;
    private ProgressBar progress;

    private Label statusLabel;
    private Label timingLabel;
    private Label sizeLabel;
    private TextArea responseBody;
    private TextArea responseHeaders;

    private TextArea bodyArea;
    private ComboBox<RestRequest.BodyType> bodyTypeCombo;

    private ComboBox<RestRequest.AuthType> authTypeCombo;
    private final TextField authUser = new TextField();
    private final PasswordField authPass = new PasswordField();
    private final TextField authToken = new TextField();
    private final TextField apiKeyName = new TextField("X-API-Key");
    private final TextField apiKeyValue = new TextField();
    private ComboBox<RestRequest.ApiKeyLocation> apiKeyLocation;
    private final TextField connectTimeoutField = new TextField();
    private final TextField readTimeoutField = new TextField();
    private final CheckBox followRedirectsBox = new CheckBox("Follow redirects automatically");

    /** Optional sink for log lines (wired to the app log panel). */
    private Consumer<String> logger = s -> {};

    /** Optional sink for completed-request history entries. */
    private Consumer<HistoryEntry> historyRecorder = e -> {};

    public RestClientView() {
        getStyleClass().add("rest-client-view");
        setTop(buildMethodBar());
        setCenter(buildSplit());
        seedExample();
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    public void setHistoryRecorder(Consumer<HistoryEntry> recorder) {
        this.historyRecorder = recorder == null ? e -> {} : recorder;
    }

    /** Pre-fills the request URL (used when opening a saved/sample connection). */
    public void prefill(String url) {
        if (url != null && !url.isBlank()) urlField.setText(url);
    }

    // ---- Method bar ----

    private VBox buildMethodBar() {
        methodCombo = new ComboBox<>(FXCollections.observableArrayList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        methodCombo.setValue("GET");
        methodCombo.setId("restMethod");
        methodCombo.setPrefWidth(110);

        urlField = new TextField();
        urlField.setId("urlBar");
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("https://api.example.com/v1/resource   (try ${BASE_URL}/users)");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setOnAction(e -> send());

        sendButton = new Button("Send");
        sendButton.getStyleClass().add("btn-primary");
        sendButton.setOnAction(e -> send());

        Button codeBtn = new Button("</>");
        codeBtn.getStyleClass().add("btn-secondary");
        codeBtn.setTooltip(new Tooltip("Generate client code"));
        codeBtn.setOnAction(e -> {
            syncModel();
            CodeGenDialog.show(getScene() == null ? null : getScene().getWindow(), request);
        });

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> HelpDialog.openContextual("urlBar"));

        HBox bar = new HBox(8, methodCombo, urlField, sendButton, codeBtn, helpBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));

        progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        progress.setPrefHeight(3);

        VBox top = new VBox(bar, progress);
        return top;
    }

    // ---- Request editor + response (vertical split) ----

    private SplitPane buildSplit() {
        SplitPane split = new SplitPane();
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().addAll(buildRequestEditor(), buildResponsePanel());
        split.setDividerPositions(0.45);
        return split;
    }

    private TabPane buildRequestEditor() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("editor-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabs.getTabs().addAll(
                new Tab("Params", buildKeyValueTable(paramRows)),
                new Tab("Headers", buildKeyValueTable(headerRows)),
                new Tab("Body", buildBodyTab()),
                new Tab("Auth", buildAuthTab()),
                new Tab("Settings", buildSettingsTab()));
        return tabs;
    }

    private BorderPane buildKeyValueTable(ObservableList<RestRequest.KeyValue> rows) {
        TableView<RestRequest.KeyValue> table = new TableView<>(rows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<RestRequest.KeyValue, Boolean> onCol = new TableColumn<>("");
        onCol.setCellValueFactory(c -> {
            var p = new SimpleBooleanProperty(c.getValue().isEnabled());
            p.addListener((o, ov, nv) -> c.getValue().setEnabled(nv));
            return p;
        });
        onCol.setCellFactory(CheckBoxTableCell.forTableColumn(onCol));
        onCol.setEditable(true);
        onCol.setMaxWidth(34);
        onCol.setMinWidth(34);

        TableColumn<RestRequest.KeyValue, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getKey()));
        keyCol.setCellFactory(TextFieldTableCell.forTableColumn());
        keyCol.setOnEditCommit(e -> {
            e.getRowValue().setKey(e.getNewValue());
            ensureTrailingRow(rows);
        });

        TableColumn<RestRequest.KeyValue, String> valCol = new TableColumn<>("Value");
        valCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getValue()));
        valCol.setCellFactory(TextFieldTableCell.forTableColumn());
        valCol.setOnEditCommit(e -> {
            e.getRowValue().setValue(e.getNewValue());
            ensureTrailingRow(rows);
        });

        table.getColumns().addAll(List.of(onCol, keyCol, valCol));
        if (rows.isEmpty()) rows.add(new RestRequest.KeyValue());

        Label hint = new Label("Double-click a cell to edit. A new row is added automatically.");
        hint.getStyleClass().add("meta-label");
        hint.setPadding(new Insets(6, 0, 0, 4));

        BorderPane pane = new BorderPane(table);
        pane.setBottom(hint);
        BorderPane.setMargin(table, new Insets(6));
        return pane;
    }

    private void ensureTrailingRow(ObservableList<RestRequest.KeyValue> rows) {
        if (rows.isEmpty() || !rows.get(rows.size() - 1).getKey().isBlank()) {
            rows.add(new RestRequest.KeyValue());
        }
    }

    private VBox buildBodyTab() {
        bodyTypeCombo = new ComboBox<>(FXCollections.observableArrayList(RestRequest.BodyType.values()));
        bodyTypeCombo.setValue(RestRequest.BodyType.NONE);

        Button format = new Button("Format JSON");
        format.getStyleClass().add("btn-secondary");
        format.setOnAction(e -> formatBody());

        HBox controls = new HBox(8, new Label("Type:"), bodyTypeCombo, format);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(6));
        ((Label) controls.getChildren().get(0)).getStyleClass().add("meta-label");

        bodyArea = new TextArea();
        bodyArea.getStyleClass().add("code-area");
        bodyArea.setPromptText("Request body…");
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        VBox box = new VBox(controls, bodyArea);
        box.setPadding(new Insets(4));
        return box;
    }

    private void formatBody() {
        try {
            Object tree = json.readValue(bodyArea.getText(), Object.class);
            bodyArea.setText(json.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
        } catch (Exception ex) {
            logger.accept("Format failed: not valid JSON — " + ex.getMessage());
        }
    }

    private GridPane buildAuthTab() {
        authTypeCombo = new ComboBox<>(FXCollections.observableArrayList(RestRequest.AuthType.values()));
        authTypeCombo.setValue(RestRequest.AuthType.NONE);
        authTypeCombo.setId("authTab");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        Label typeLbl = new Label("Auth Type:");
        typeLbl.getStyleClass().add("meta-label");
        grid.add(typeLbl, 0, 0);
        grid.add(authTypeCombo, 1, 0);

        Label userLbl = new Label("Username:");
        Label passLbl = new Label("Password:");
        Label tokenLbl = new Label("Token:");
        userLbl.getStyleClass().add("meta-label");
        passLbl.getStyleClass().add("meta-label");
        tokenLbl.getStyleClass().add("meta-label");
        authUser.getStyleClass().add("nl-field");
        authPass.getStyleClass().add("nl-field");
        authToken.getStyleClass().add("nl-field");
        authUser.setPrefWidth(280);
        authToken.setPrefWidth(280);

        Label keyNameLbl = new Label("Key name:");
        Label keyValueLbl = new Label("Key value:");
        Label keyInLbl = new Label("Add to:");
        keyNameLbl.getStyleClass().add("meta-label");
        keyValueLbl.getStyleClass().add("meta-label");
        keyInLbl.getStyleClass().add("meta-label");
        apiKeyName.getStyleClass().add("nl-field");
        apiKeyValue.getStyleClass().add("nl-field");
        apiKeyName.setPrefWidth(280);
        apiKeyValue.setPrefWidth(280);
        apiKeyLocation = new ComboBox<>(FXCollections.observableArrayList(RestRequest.ApiKeyLocation.values()));
        apiKeyLocation.setValue(RestRequest.ApiKeyLocation.HEADER);

        grid.add(userLbl, 0, 1);  grid.add(authUser, 1, 1);
        grid.add(passLbl, 0, 2);  grid.add(authPass, 1, 2);
        grid.add(tokenLbl, 0, 3); grid.add(authToken, 1, 3);
        grid.add(keyNameLbl, 0, 4);  grid.add(apiKeyName, 1, 4);
        grid.add(keyValueLbl, 0, 5); grid.add(apiKeyValue, 1, 5);
        grid.add(keyInLbl, 0, 6);    grid.add(apiKeyLocation, 1, 6);

        Runnable refresh = () -> {
            RestRequest.AuthType t = authTypeCombo.getValue();
            boolean basic = t == RestRequest.AuthType.BASIC;
            boolean bearer = t == RestRequest.AuthType.BEARER;
            boolean apiKey = t == RestRequest.AuthType.API_KEY;
            setRowVisible(basic, userLbl, authUser, passLbl, authPass);
            setRowVisible(bearer, tokenLbl, authToken);
            setRowVisible(apiKey, keyNameLbl, apiKeyName, keyValueLbl, apiKeyValue, keyInLbl, apiKeyLocation);
        };
        authTypeCombo.valueProperty().addListener((o, ov, nv) -> refresh.run());
        refresh.run();

        return grid;
    }

    private static void setRowVisible(boolean visible, javafx.scene.Node... nodes) {
        for (javafx.scene.Node n : nodes) { n.setVisible(visible); n.setManaged(visible); }
    }

    private GridPane buildSettingsTab() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        connectTimeoutField.getStyleClass().add("nl-field");
        readTimeoutField.getStyleClass().add("nl-field");
        connectTimeoutField.setPrefWidth(120);
        readTimeoutField.setPrefWidth(120);
        connectTimeoutField.setText(String.valueOf(request.getConnectTimeoutMs()));
        readTimeoutField.setText(String.valueOf(request.getReadTimeoutMs()));
        followRedirectsBox.setSelected(request.isFollowRedirects());

        Label ctLbl = new Label("Connect timeout (ms):");
        Label rtLbl = new Label("Read timeout (ms):");
        ctLbl.getStyleClass().add("meta-label");
        rtLbl.getStyleClass().add("meta-label");

        grid.add(ctLbl, 0, 0); grid.add(connectTimeoutField, 1, 0);
        grid.add(rtLbl, 0, 1); grid.add(readTimeoutField, 1, 1);
        grid.add(followRedirectsBox, 1, 2);
        return grid;
    }

    private static int parseIntOr(String s, int fallback) {
        try { return Math.max(0, Integer.parseInt(s.trim())); }
        catch (NumberFormatException e) { return fallback; }
    }

    // ---- Response panel ----

    private BorderPane buildResponsePanel() {
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("meta-label");
        timingLabel = new Label();
        timingLabel.getStyleClass().add("meta-label");
        sizeLabel = new Label();
        sizeLabel.getStyleClass().add("meta-label");

        HBox meta = new HBox(16, statusLabel, timingLabel, sizeLabel);
        meta.setAlignment(Pos.CENTER_LEFT);
        meta.setPadding(new Insets(8, 10, 8, 10));

        responseBody = new TextArea();
        responseBody.setEditable(false);
        responseBody.getStyleClass().add("code-area");

        responseHeaders = new TextArea();
        responseHeaders.setEditable(false);
        responseHeaders.getStyleClass().add("code-area");

        TabPane respTabs = new TabPane();
        respTabs.getStyleClass().add("editor-tabs");
        respTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        respTabs.getTabs().addAll(
                new Tab("Body", responseBody),
                new Tab("Headers", responseHeaders));

        BorderPane pane = new BorderPane(respTabs);
        pane.setTop(meta);
        return pane;
    }

    // ---- Execution ----

    /** Public entry point for the shell's Ctrl+Enter accelerator. */
    public void sendRequest() {
        send();
    }

    private void send() {
        syncModel();
        if (request.getUrl().isBlank()) {
            statusLabel.setText("Enter a URL first");
            return;
        }

        sendButton.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        statusLabel.setText("Sending…");
        timingLabel.setText("");
        sizeLabel.setText("");
        logger.accept(request.getMethod() + " " + request.effectiveUrl());

        Task<RestResponse> task = new Task<>() {
            @Override protected RestResponse call() {
                return executor.execute(request);
            }
        };
        task.setOnSucceeded(e -> { renderResponse(task.getValue()); finishSend(); });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Error: " + task.getException());
            finishSend();
        });
        Thread t = new Thread(task, "rest-exec");
        t.setDaemon(true);
        t.start();
    }

    private void finishSend() {
        sendButton.setDisable(false);
        progress.setVisible(false);
        progress.setManaged(false);
    }

    private void syncModel() {
        request.setMethod(methodCombo.getValue());
        request.setUrl(urlField.getText().trim());
        request.getQueryParams().clear();
        request.getQueryParams().addAll(paramRows);
        request.getHeaders().clear();
        request.getHeaders().addAll(headerRows);
        request.setBodyType(bodyTypeCombo.getValue());
        request.setBody(bodyArea.getText());
        request.setAuthType(authTypeCombo.getValue());
        request.setAuthUsername(authUser.getText());
        request.setAuthPassword(authPass.getText());
        request.setAuthToken(authToken.getText());
        request.setApiKeyName(apiKeyName.getText());
        request.setApiKeyValue(apiKeyValue.getText());
        request.setApiKeyLocation(apiKeyLocation.getValue());
        request.setConnectTimeoutMs(parseIntOr(connectTimeoutField.getText(), 10_000));
        request.setReadTimeoutMs(parseIntOr(readTimeoutField.getText(), 30_000));
        request.setFollowRedirects(followRedirectsBox.isSelected());
    }

    private void renderResponse(RestResponse resp) {
        if (resp.failed()) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + resp.errorMessage());
            timingLabel.setText(resp.timing().totalMs() + " ms");
            responseBody.setText("Request failed:\n\n" + resp.errorMessage()
                    + "\n\nPress F1 → Troubleshooting for common fixes.");
            responseHeaders.clear();
            logger.accept("FAILED — " + resp.errorMessage());
            recordHistory("✖ " + request.getMethod() + " " + request.getUrl()
                    + " — " + resp.errorMessage(), 0, resp.timing().totalMs());
            return;
        }

        statusLabel.getStyleClass().setAll("status-" + resp.statusClass() + "xx");
        statusLabel.setText(resp.statusCode() + " " + resp.statusText());
        timingLabel.setText(resp.timing().totalMs() + " ms  (ttfb "
                + resp.timing().ttfbMs() + " · dl " + resp.timing().downloadMs() + ")");
        sizeLabel.setText(resp.prettyBytes() + "  ·  " + resp.httpVersion());

        responseBody.setText(prettyIfJson(resp.body(), resp.headers()));
        responseHeaders.setText(formatHeaders(resp.headers()));
        logger.accept(resp.statusCode() + " " + resp.statusText()
                + "  " + resp.timing().totalMs() + "ms  " + resp.prettyBytes());
        recordHistory(request.getMethod() + " " + request.getUrl()
                + " → " + resp.statusCode(), resp.statusCode(), resp.timing().totalMs());
    }

    private void recordHistory(String summary, int statusCode, long durationMs) {
        try {
            historyRecorder.accept(HistoryEntry.newRest(
                    summary, statusCode, durationMs, serializeRequest()));
        } catch (Exception ignored) {
            // history is best-effort; never block the response path
        }
    }

    // ---- Request (de)serialization for history replay ----

    /** Serialize the current request to JSON (for history replay). */
    public String serializeRequest() {
        ObjectNode root = json.createObjectNode();
        root.put("method", request.getMethod());
        root.put("url", request.getUrl());
        root.put("bodyType", request.getBodyType().name());
        root.put("body", request.getBody());
        root.put("authType", request.getAuthType().name());
        root.put("authUsername", request.getAuthUsername());
        root.put("authToken", request.getAuthToken());
        root.put("apiKeyName", request.getApiKeyName());
        root.put("apiKeyValue", request.getApiKeyValue());
        root.put("apiKeyLocation", request.getApiKeyLocation().name());
        putKeyValues(root.putArray("params"), paramRows);
        putKeyValues(root.putArray("headers"), headerRows);
        return root.toString();
    }

    private void putKeyValues(ArrayNode arr, List<RestRequest.KeyValue> rows) {
        for (RestRequest.KeyValue kv : rows) {
            if (kv.getKey().isBlank()) continue;
            ObjectNode n = arr.addObject();
            n.put("enabled", kv.isEnabled());
            n.put("key", kv.getKey());
            n.put("value", kv.getValue());
        }
    }

    /** Populate this view from a serialized request (history replay). */
    public void loadRequest(String detailJson) {
        try {
            var root = json.readTree(detailJson);
            methodCombo.setValue(root.path("method").asText("GET"));
            urlField.setText(root.path("url").asText(""));
            bodyTypeCombo.setValue(RestRequest.BodyType.valueOf(
                    root.path("bodyType").asText("NONE")));
            bodyArea.setText(root.path("body").asText(""));
            authTypeCombo.setValue(RestRequest.AuthType.valueOf(
                    root.path("authType").asText("NONE")));
            authUser.setText(root.path("authUsername").asText(""));
            authToken.setText(root.path("authToken").asText(""));
            apiKeyName.setText(root.path("apiKeyName").asText("X-API-Key"));
            apiKeyValue.setText(root.path("apiKeyValue").asText(""));
            apiKeyLocation.setValue(RestRequest.ApiKeyLocation.valueOf(
                    root.path("apiKeyLocation").asText("HEADER")));
            loadKeyValues(root.path("params"), paramRows);
            loadKeyValues(root.path("headers"), headerRows);
        } catch (Exception e) {
            logger.accept("Replay failed: " + e.getMessage());
        }
    }

    private void loadKeyValues(com.fasterxml.jackson.databind.JsonNode arr,
                               ObservableList<RestRequest.KeyValue> rows) {
        rows.clear();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                RestRequest.KeyValue kv = new RestRequest.KeyValue(
                        n.path("key").asText(""), n.path("value").asText(""));
                kv.setEnabled(n.path("enabled").asBoolean(true));
                rows.add(kv);
            });
        }
        rows.add(new RestRequest.KeyValue()); // trailing empty row
    }

    private String prettyIfJson(String body, Map<String, List<String>> headers) {
        boolean looksJson = headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("content-type"))
                .flatMap(e -> e.getValue().stream())
                .anyMatch(v -> v.toLowerCase().contains("json"));
        if (!looksJson) return body;
        try {
            Object tree = json.readValue(body, Object.class);
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception ignored) {
            return body;
        }
    }

    private String formatHeaders(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, vals) -> vals.forEach(v -> sb.append(k).append(": ").append(v).append('\n')));
        return sb.toString();
    }

    private void seedExample() {
        urlField.setText("https://httpbin.org/get");
        headerRows.add(new RestRequest.KeyValue("Accept", "application/json"));
        headerRows.add(new RestRequest.KeyValue());
        paramRows.add(new RestRequest.KeyValue());
    }
}
