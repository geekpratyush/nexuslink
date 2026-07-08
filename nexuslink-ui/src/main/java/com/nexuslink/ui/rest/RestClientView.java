package com.nexuslink.ui.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexuslink.core.connection.AuthMethod;
import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.core.env.EnvironmentService;
import com.nexuslink.core.env.VariableInterpolator;
import com.nexuslink.core.history.HistoryEntry;
import com.nexuslink.ui.env.Env;
import com.nexuslink.protocol.http.rest.AssertionSpec;
import com.nexuslink.protocol.http.rest.BodyFormatter;
import com.nexuslink.protocol.http.rest.CurlImporter;
import com.nexuslink.protocol.http.rest.HarExporter;
import com.nexuslink.protocol.http.rest.PreRequestScript;
import com.nexuslink.protocol.http.rest.ResponseAssertions;
import com.nexuslink.protocol.http.rest.RestExecutionService;
import com.nexuslink.protocol.http.rest.RestRequest;
import com.nexuslink.protocol.http.rest.RestResponse;
import com.nexuslink.ui.help.HelpDialog;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

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
    private final ObservableList<AssertionSpec> assertionRows = FXCollections.observableArrayList();

    /** Every request/response executed in this tab, retained so the session can be exported as HAR. */
    private final List<HarExporter.Entry> harEntries = new ArrayList<>();

    private ComboBox<String> methodCombo;
    private TextField urlField;
    private Button sendButton;
    private ProgressBar progress;

    private Label statusLabel;
    private Label timingLabel;
    private Label sizeLabel;
    private final com.nexuslink.ui.hint.ErrorHelpLink errorHelpLink = new com.nexuslink.ui.hint.ErrorHelpLink();
    private org.fxmisc.richtext.CodeArea responseBody;
    private ComboBox<BodyFormatter.Mode> bodyViewMode;
    private RestResponse lastResponse;
    private TextArea responseHeaders;
    private TextArea responseCookies;
    private TextArea responseTests;
    private Tab responseTestsTab;
    private TimelineView responseTimeline;
    private final com.nexuslink.ui.trace.TraceTreeView traceTree = new com.nexuslink.ui.trace.TraceTreeView();

    private TextArea bodyArea;
    private ComboBox<RestRequest.BodyType> bodyTypeCombo;
    private TextArea preRequestArea;

    private ComboBox<RestRequest.AuthType> authTypeCombo;
    private final TextField authUser = new TextField();
    private final PasswordField authPass = new PasswordField();
    private final TextField authToken = new TextField();
    private final TextField apiKeyName = new TextField("X-API-Key");
    private final TextField apiKeyValue = new TextField();
    private ComboBox<RestRequest.ApiKeyLocation> apiKeyLocation;
    private final TextField oauthTokenUrl = new TextField();
    private final TextField oauthClientId = new TextField();
    private final PasswordField oauthClientSecret = new PasswordField();
    private final TextField oauthScope = new TextField();
    private final TextField awsRegion = new TextField("us-east-1");
    private final TextField awsService = new TextField("execute-api");
    private final TextField awsAccessKey = new TextField();
    private final PasswordField awsSecretKey = new PasswordField();
    private final TextField awsSessionToken = new TextField();
    private ComboBox<com.nexuslink.protocol.http.rest.HmacAuthenticator.Algorithm> hmacAlgorithm;
    private ComboBox<com.nexuslink.protocol.http.rest.HmacAuthenticator.Encoding> hmacEncoding;
    private final TextField hmacKeyId = new TextField();
    private final PasswordField hmacSecret = new PasswordField();
    private final TextField ntlmDomain = new TextField();
    private final TextField ntlmUsername = new TextField();
    private final PasswordField ntlmPassword = new PasswordField();
    private final TextField ntlmWorkstation = new TextField();
    private final TextField hmacStringToSign = new TextField("{method}\\n{path}\\n{date}");
    private final TextField hmacHeaderName = new TextField("Authorization");
    private final TextField hmacHeaderValue = new TextField("HMAC {signature}");
    private final TextField tlsTrustStore = new TextField();
    private final PasswordField tlsTrustStorePw = new PasswordField();
    private final TextField tlsKeyStore = new TextField();
    private final PasswordField tlsKeyStorePw = new PasswordField();
    private final CheckBox tlsTrustAll = new CheckBox("Trust all certificates (insecure — testing only)");
    private final TextField connectTimeoutField = new TextField();
    private final TextField readTimeoutField = new TextField();
    private final CheckBox followRedirectsBox = new CheckBox("Follow redirects automatically");
    private final CheckBox traceBox = new CheckBox("Inject W3C traceparent + capture a span per request");

    /** Optional sink for log lines (wired to the app log panel). */
    private Consumer<String> logger = s -> {};
    private Consumer<ConnectionProfile> onSave = p -> {};

    /** Optional sink for completed-request history entries. */
    private Consumer<HistoryEntry> historyRecorder = e -> {};

    public RestClientView() {
        getStyleClass().add("rest-client-view");
        seedTimeoutDefaults();
        setTop(buildMethodBar());
        setCenter(buildSplit());
        seedExample();
    }

    /** Seed this tab's default timeouts from the user's saved preferences. */
    private void seedTimeoutDefaults() {
        try {
            var settings = com.nexuslink.ui.settings.Settings.service();
            request.setConnectTimeoutMs(settings.getConnectTimeoutMs());
            request.setReadTimeoutMs(settings.getReadTimeoutMs());
        } catch (Exception ignored) {
            // Preferences are best-effort; fall back to the request's built-in defaults.
        }
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

    /** Notified when the user saves the current request as a connection. */
    public void setOnSave(Consumer<ConnectionProfile> onSave) {
        this.onSave = onSave == null ? p -> {} : onSave;
    }

    private void saveCurrent() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) { statusLabel.setText("Enter a URL before saving"); return; }
        TextInputDialog dialog = new TextInputDialog(url);
        dialog.setTitle("Save connection");
        dialog.setHeaderText("Save this request as a connection");
        dialog.setContentText("Name:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            onSave.accept(toProfile(name.trim()));
            logger.accept("Saved REST connection: " + name.trim());
        });
    }

    /** Builds a connection profile from the current request (secrets in plaintext authProps;
     *  the caller is expected to move them into the vault). */
    private ConnectionProfile toProfile(String name) {
        syncModel();
        ConnectionProfile p = new ConnectionProfile(name, ConnectionProfile.Protocol.REST, request.getUrl());
        switch (request.getAuthType()) {
            case BASIC -> p.withUser(request.getAuthUsername()).withAuth(AuthMethod.BASIC)
                    .authProp("password", request.getAuthPassword());
            case BEARER -> p.withAuth(AuthMethod.BEARER_TOKEN).authProp("token", request.getAuthToken());
            case API_KEY -> p.withAuth(AuthMethod.API_KEY)
                    .authProp("apiKeyName", request.getApiKeyName())
                    .authProp("apiKeyValue", request.getApiKeyValue())
                    .authProp("apiKeyIn", request.getApiKeyLocation().name());
            case OAUTH2 -> p.withAuth(AuthMethod.OAUTH2)
                    .authProp("tokenUrl", request.getOauthTokenUrl())
                    .authProp("clientId", request.getOauthClientId())
                    .authProp("clientSecret", request.getOauthClientSecret())
                    .authProp("scope", request.getOauthScope());
            case NONE -> p.withAuth(AuthMethod.NONE);
        }
        return p;
    }

    /** Applies a saved connection profile (URL + auth) to the editor. Secrets are expected to be
     *  already resolved into plaintext authProps by the caller. */
    public void applyProfile(ConnectionProfile p) {
        if (p.target != null) urlField.setText(p.target);
        var a = p.authProps;
        switch (p.auth) {
            case BASIC -> {
                authTypeCombo.setValue(RestRequest.AuthType.BASIC);
                authUser.setText(p.username);
                authPass.setText(a.getOrDefault("password", ""));
            }
            case BEARER_TOKEN -> {
                authTypeCombo.setValue(RestRequest.AuthType.BEARER);
                authToken.setText(a.getOrDefault("token", ""));
            }
            case API_KEY -> {
                authTypeCombo.setValue(RestRequest.AuthType.API_KEY);
                apiKeyName.setText(a.getOrDefault("apiKeyName", "X-API-Key"));
                apiKeyValue.setText(a.getOrDefault("apiKeyValue", ""));
                apiKeyLocation.setValue(RestRequest.ApiKeyLocation.valueOf(a.getOrDefault("apiKeyIn", "HEADER")));
            }
            case OAUTH2 -> {
                authTypeCombo.setValue(RestRequest.AuthType.OAUTH2);
                oauthTokenUrl.setText(a.getOrDefault("tokenUrl", ""));
                oauthClientId.setText(a.getOrDefault("clientId", ""));
                oauthClientSecret.setText(a.getOrDefault("clientSecret", ""));
                oauthScope.setText(a.getOrDefault("scope", ""));
            }
            default -> authTypeCombo.setValue(RestRequest.AuthType.NONE);
        }
    }

    // ---- Method bar ----

    /** Prompts for a curl command and populates the editor from {@link CurlImporter}. */
    private void importCurl() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Import cURL");
        dialog.setHeaderText("Paste a curl command:");
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
        TextArea area = new TextArea();
        area.setPromptText("curl -X POST https://api.example.com/v1/things -H 'Content-Type: application/json' -d '{\"a\":1}'");
        area.setPrefRowCount(8);
        area.setPrefColumnCount(60);
        area.setWrapText(true);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ButtonType.OK ? area.getText() : null);
        dialog.showAndWait().ifPresent(text -> {
            if (text == null || text.isBlank()) return;
            try {
                applyImported(CurlImporter.fromCurl(text));
                logger.accept("Imported cURL request");
            } catch (IllegalArgumentException ex) {
                logger.accept("cURL import failed: " + ex.getMessage());
            }
        });
    }

    /** Saves every request/response executed in this tab as an HTTP Archive (HAR 1.2) document. */
    private void exportHar() {
        if (harEntries.isEmpty()) {
            logger.accept("Nothing to export — send a request first");
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export HAR");
        fc.setInitialFileName("nexuslink.har");
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("HTTP Archive", "*.har"),
                new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), HarExporter.toHar(harEntries), StandardCharsets.UTF_8);
            logger.accept("Exported " + harEntries.size() + " request(s) to " + file.getName());
        } catch (java.io.IOException ex) {
            logger.accept("HAR export failed: " + ex.getMessage());
        }
    }

    /** Saves the spans captured from traced requests in this tab as Zipkin v2 JSON. */
    private void exportTrace() {
        var spans = executor.capturedSpans();
        if (spans.isEmpty()) {
            logger.accept("No trace captured — enable tracing in Settings and send a request first");
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Export trace (Zipkin v2)");
        fc.setInitialFileName("nexuslink-trace.json");
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("Zipkin JSON", "*.json"),
                new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"));
        java.io.File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(),
                    com.nexuslink.protocol.http.rest.ZipkinSpanExporter.toJsonArray(spans), StandardCharsets.UTF_8);
            logger.accept("Exported " + spans.size() + " span(s) to " + file.getName());
        } catch (java.io.IOException ex) {
            logger.accept("Trace export failed: " + ex.getMessage());
        }
    }

    /** Pushes a parsed {@link RestRequest} into the editor's visible fields. */
    private void applyImported(RestRequest r) {
        methodCombo.setValue(r.getMethod());
        urlField.setText(r.getUrl());
        paramRows.setAll(r.getQueryParams());
        headerRows.setAll(r.getHeaders());
        bodyTypeCombo.setValue(r.getBodyType());
        bodyArea.setText(r.getBody());
        // Setting the auth type fires the combo listener, which re-runs the auth-field visibility.
        authTypeCombo.setValue(r.getAuthType());
        authUser.setText(r.getAuthUsername());
        authPass.setText(r.getAuthPassword());
        authToken.setText(r.getAuthToken());
    }

    private VBox buildMethodBar() {
        methodCombo = new ComboBox<>(FXCollections.observableArrayList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"));
        methodCombo.setValue("GET");
        methodCombo.setId("restMethod");
        methodCombo.setPrefWidth(110);
        com.nexuslink.ui.util.HttpMethods.styleCombo(methodCombo);

        urlField = new TextField();
        urlField.setId("urlBar");
        urlField.getStyleClass().add("nl-field");
        urlField.setPromptText("https://api.example.com/v1/resource   (try ${BASE_URL}/users)");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setOnAction(e -> send());
        com.nexuslink.ui.hint.TooltipPlus.attach(urlField,
                "The request URL. Supports ${VAR} substitution from the active environment.",
                "rest-client#url-bar");

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

        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().add("btn-secondary");
        saveBtn.setTooltip(new Tooltip("Save this request as a connection"));
        saveBtn.setOnAction(e -> saveCurrent());

        Button curlBtn = new Button("Import cURL");
        curlBtn.getStyleClass().add("btn-secondary");
        curlBtn.setTooltip(new Tooltip("Paste a curl command to populate this request"));
        curlBtn.setOnAction(e -> importCurl());

        Button harBtn = new Button("HAR");
        harBtn.getStyleClass().add("btn-secondary");
        harBtn.setTooltip(new Tooltip("Export the requests sent in this tab as an HTTP Archive (.har)"));
        harBtn.setOnAction(e -> exportHar());

        Button traceBtn = new Button("Trace");
        traceBtn.getStyleClass().add("btn-secondary");
        traceBtn.setTooltip(new Tooltip("Export captured trace spans as Zipkin v2 JSON (enable tracing in Settings)"));
        traceBtn.setOnAction(e -> exportTrace());

        com.nexuslink.ui.hint.HelpButton helpBtn =
                new com.nexuslink.ui.hint.HelpButton("rest-client#url-bar", "Help for the REST client");

        HBox bar = new HBox(8, methodCombo, urlField, sendButton, codeBtn, curlBtn, saveBtn, harBtn, traceBtn, helpBtn);
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
                new Tab("Pre-request Script", buildPreRequestTab()),
                new Tab("Tests", buildAssertionsTable()),
                new Tab("Settings", buildSettingsTab()));
        return tabs;
    }

    /** Editable table of response assertions; evaluated after each call into the Test Results tab. */
    private BorderPane buildAssertionsTable() {
        TableView<AssertionSpec> table = new TableView<>(assertionRows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<AssertionSpec, Boolean> onCol = new TableColumn<>("");
        onCol.setCellValueFactory(c -> {
            var p = new SimpleBooleanProperty(c.getValue().isEnabled());
            p.addListener((o, ov, nv) -> c.getValue().setEnabled(nv));
            return p;
        });
        onCol.setCellFactory(CheckBoxTableCell.forTableColumn(onCol));
        onCol.setEditable(true);
        onCol.setMaxWidth(34);
        onCol.setMinWidth(34);

        TableColumn<AssertionSpec, ResponseAssertions.Type> typeCol = new TableColumn<>("Check");
        typeCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getType()));
        typeCol.setCellFactory(ComboBoxTableCell.forTableColumn(ResponseAssertions.Type.values()));
        typeCol.setOnEditCommit(e -> {
            e.getRowValue().setType(e.getNewValue());
            ensureTrailingAssertion();
        });
        typeCol.setPrefWidth(150);

        TableColumn<AssertionSpec, String> nameCol = new TableColumn<>("Header / JSON path");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        nameCol.setOnEditCommit(e -> { e.getRowValue().setName(e.getNewValue()); ensureTrailingAssertion(); });

        TableColumn<AssertionSpec, String> targetCol = new TableColumn<>("Expected / min");
        targetCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getTarget()));
        targetCol.setCellFactory(TextFieldTableCell.forTableColumn());
        targetCol.setOnEditCommit(e -> { e.getRowValue().setTarget(e.getNewValue()); ensureTrailingAssertion(); });

        TableColumn<AssertionSpec, String> maxCol = new TableColumn<>("Max");
        maxCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getMax()));
        maxCol.setCellFactory(TextFieldTableCell.forTableColumn());
        maxCol.setOnEditCommit(e -> { e.getRowValue().setMax(e.getNewValue()); ensureTrailingAssertion(); });
        maxCol.setMaxWidth(90);

        table.getColumns().addAll(List.of(onCol, typeCol, nameCol, targetCol, maxCol));
        if (assertionRows.isEmpty()) assertionRows.add(new AssertionSpec());

        Label hint = new Label("Assertions run automatically after each response. "
                + "Header/JSON-path → use the path column; status range → Expected=min, Max=max.");
        hint.getStyleClass().add("meta-label");
        hint.setPadding(new Insets(6, 0, 0, 4));
        hint.setWrapText(true);

        BorderPane pane = new BorderPane(table);
        pane.setBottom(hint);
        BorderPane.setMargin(table, new Insets(6));
        return pane;
    }

    private void ensureTrailingAssertion() {
        if (assertionRows.isEmpty() || assertionRows.get(assertionRows.size() - 1).isComplete()) {
            assertionRows.add(new AssertionSpec());
        }
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

    private VBox buildPreRequestTab() {
        Label hint = new Label(
                "Runs before Send. One statement per line: set VAR = <expr>. "
                + "Functions: now(), isoNow(), uuid(), base64(x), hmacSha256(key, msg), "
                + "randomInt(min, max). Reference vars/env with ${NAME}; use them in the request as ${VAR}.");
        hint.getStyleClass().add("meta-label");
        hint.setWrapText(true);
        hint.setPadding(new Insets(6));

        preRequestArea = new TextArea();
        preRequestArea.getStyleClass().add("code-area");
        preRequestArea.setPromptText(
                "# e.g.\nset TS = now()\nset NONCE = uuid()\nset SIG = hmacSha256(${API_SECRET}, ${TS})");
        VBox.setVgrow(preRequestArea, Priority.ALWAYS);

        VBox box = new VBox(hint, preRequestArea);
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

        Label tokenUrlLbl = new Label("Token URL:");
        Label clientIdLbl = new Label("Client ID:");
        Label clientSecretLbl = new Label("Client secret:");
        Label scopeLbl = new Label("Scope:");
        for (Label l : new Label[]{tokenUrlLbl, clientIdLbl, clientSecretLbl, scopeLbl}) l.getStyleClass().add("meta-label");
        for (TextField f : new TextField[]{oauthTokenUrl, oauthClientId, oauthClientSecret, oauthScope}) {
            f.getStyleClass().add("nl-field");
            f.setPrefWidth(280);
        }
        oauthScope.setPromptText("optional, space-separated");
        grid.add(tokenUrlLbl, 0, 7);     grid.add(oauthTokenUrl, 1, 7);
        grid.add(clientIdLbl, 0, 8);     grid.add(oauthClientId, 1, 8);
        grid.add(clientSecretLbl, 0, 9); grid.add(oauthClientSecret, 1, 9);
        grid.add(scopeLbl, 0, 10);       grid.add(oauthScope, 1, 10);

        Label grantLbl = new Label("Grant:");
        grantLbl.getStyleClass().add("meta-label");
        Label grantHint = new Label("Token URL above = client-credentials (automatic on Send).");
        grantHint.getStyleClass().add("meta-label");
        Button authCodeBtn = new Button("Authorization Code + PKCE…");
        authCodeBtn.getStyleClass().add("btn-secondary");
        authCodeBtn.setOnAction(e -> runAuthorizationCodeFlow());
        javafx.scene.layout.VBox grantBox = new javafx.scene.layout.VBox(4, authCodeBtn, grantHint);
        grid.add(grantLbl, 0, 11);       grid.add(grantBox, 1, 11);

        Label regionLbl = new Label("AWS region:");
        Label serviceLbl = new Label("AWS service:");
        Label accessKeyLbl = new Label("Access key:");
        Label secretKeyLbl = new Label("Secret key:");
        Label sessionTokenLbl = new Label("Session token:");
        for (Label l : new Label[]{regionLbl, serviceLbl, accessKeyLbl, secretKeyLbl, sessionTokenLbl}) l.getStyleClass().add("meta-label");
        for (TextField f : new TextField[]{awsRegion, awsService, awsAccessKey, awsSecretKey, awsSessionToken}) {
            f.getStyleClass().add("nl-field");
            f.setPrefWidth(280);
        }
        awsSessionToken.setPromptText("optional, for temporary credentials");
        grid.add(regionLbl, 0, 12);       grid.add(awsRegion, 1, 12);
        grid.add(serviceLbl, 0, 13);      grid.add(awsService, 1, 13);
        grid.add(accessKeyLbl, 0, 14);    grid.add(awsAccessKey, 1, 14);
        grid.add(secretKeyLbl, 0, 15);    grid.add(awsSecretKey, 1, 15);
        grid.add(sessionTokenLbl, 0, 16); grid.add(awsSessionToken, 1, 16);

        hmacAlgorithm = new ComboBox<>(FXCollections.observableArrayList(
                com.nexuslink.protocol.http.rest.HmacAuthenticator.Algorithm.values()));
        hmacAlgorithm.setValue(com.nexuslink.protocol.http.rest.HmacAuthenticator.Algorithm.HMAC_SHA256);
        hmacEncoding = new ComboBox<>(FXCollections.observableArrayList(
                com.nexuslink.protocol.http.rest.HmacAuthenticator.Encoding.values()));
        hmacEncoding.setValue(com.nexuslink.protocol.http.rest.HmacAuthenticator.Encoding.BASE64);
        Label hmacAlgoLbl = new Label("Algorithm:");
        Label hmacKeyIdLbl = new Label("Key ID:");
        Label hmacSecretLbl = new Label("Secret:");
        Label hmacEncLbl = new Label("Encoding:");
        Label hmacStsLbl = new Label("String to sign:");
        Label hmacHdrNameLbl = new Label("Header name:");
        Label hmacHdrValLbl = new Label("Header value:");
        for (Label l : new Label[]{hmacAlgoLbl, hmacKeyIdLbl, hmacSecretLbl, hmacEncLbl,
                hmacStsLbl, hmacHdrNameLbl, hmacHdrValLbl}) l.getStyleClass().add("meta-label");
        for (TextField f : new TextField[]{hmacKeyId, hmacSecret, hmacStringToSign, hmacHeaderName, hmacHeaderValue}) {
            f.getStyleClass().add("nl-field");
            f.setPrefWidth(280);
        }
        hmacKeyId.setPromptText("optional, e.g. access key id");
        Label hmacHint = new Label("Placeholders: {method} {path} {query} {url} {host} {date} "
                + "{body} {body-sha256-hex} {body-sha256-base64} {keyId} {signature}; use \\n for a newline.");
        hmacHint.getStyleClass().add("meta-label");
        hmacHint.setWrapText(true);
        hmacHint.setMaxWidth(380);
        grid.add(hmacAlgoLbl, 0, 17);     grid.add(hmacAlgorithm, 1, 17);
        grid.add(hmacKeyIdLbl, 0, 18);    grid.add(hmacKeyId, 1, 18);
        grid.add(hmacSecretLbl, 0, 19);   grid.add(hmacSecret, 1, 19);
        grid.add(hmacEncLbl, 0, 20);      grid.add(hmacEncoding, 1, 20);
        grid.add(hmacStsLbl, 0, 21);      grid.add(hmacStringToSign, 1, 21);
        grid.add(hmacHdrNameLbl, 0, 22);  grid.add(hmacHeaderName, 1, 22);
        grid.add(hmacHdrValLbl, 0, 23);   grid.add(hmacHeaderValue, 1, 23);
        grid.add(hmacHint, 1, 24);

        Label ntlmDomainLbl = new Label("Domain:");
        Label ntlmUserLbl = new Label("Username:");
        Label ntlmPassLbl = new Label("Password:");
        Label ntlmWsLbl = new Label("Workstation:");
        for (Label l : new Label[]{ntlmDomainLbl, ntlmUserLbl, ntlmPassLbl, ntlmWsLbl}) l.getStyleClass().add("meta-label");
        for (TextField f : new TextField[]{ntlmDomain, ntlmUsername, ntlmPassword, ntlmWorkstation}) {
            f.getStyleClass().add("nl-field");
            f.setPrefWidth(280);
        }
        ntlmDomain.setPromptText("Windows / AD domain");
        ntlmWorkstation.setPromptText("optional client host name");
        Label ntlmHint = new Label("NTLMv2 challenge-response. Note: java.net.http reuses a pooled "
                + "connection for the challenge round-trip; servers that strictly pin auth to one TCP "
                + "connection may not be supported.");
        ntlmHint.getStyleClass().add("meta-label");
        ntlmHint.setWrapText(true);
        ntlmHint.setMaxWidth(380);
        grid.add(ntlmDomainLbl, 0, 25);  grid.add(ntlmDomain, 1, 25);
        grid.add(ntlmUserLbl, 0, 26);    grid.add(ntlmUsername, 1, 26);
        grid.add(ntlmPassLbl, 0, 27);    grid.add(ntlmPassword, 1, 27);
        grid.add(ntlmWsLbl, 0, 28);      grid.add(ntlmWorkstation, 1, 28);
        grid.add(ntlmHint, 1, 29);

        Runnable refresh = () -> {
            RestRequest.AuthType t = authTypeCombo.getValue();
            boolean basic = t == RestRequest.AuthType.BASIC;
            boolean digest = t == RestRequest.AuthType.DIGEST;
            boolean bearer = t == RestRequest.AuthType.BEARER;
            boolean apiKey = t == RestRequest.AuthType.API_KEY;
            boolean oauth = t == RestRequest.AuthType.OAUTH2;
            boolean sigv4 = t == RestRequest.AuthType.AWS_SIGV4;
            boolean hmac = t == RestRequest.AuthType.HMAC;
            boolean ntlm = t == RestRequest.AuthType.NTLM;
            // Basic + Digest both use the username/password fields.
            setRowVisible(basic || digest, userLbl, authUser, passLbl, authPass);
            setRowVisible(bearer, tokenLbl, authToken);
            setRowVisible(apiKey, keyNameLbl, apiKeyName, keyValueLbl, apiKeyValue, keyInLbl, apiKeyLocation);
            setRowVisible(oauth, tokenUrlLbl, oauthTokenUrl, clientIdLbl, oauthClientId,
                    clientSecretLbl, oauthClientSecret, scopeLbl, oauthScope, grantLbl, grantBox);
            setRowVisible(sigv4, regionLbl, awsRegion, serviceLbl, awsService, accessKeyLbl, awsAccessKey,
                    secretKeyLbl, awsSecretKey, sessionTokenLbl, awsSessionToken);
            setRowVisible(hmac, hmacAlgoLbl, hmacAlgorithm, hmacKeyIdLbl, hmacKeyId, hmacSecretLbl, hmacSecret,
                    hmacEncLbl, hmacEncoding, hmacStsLbl, hmacStringToSign, hmacHdrNameLbl, hmacHeaderName,
                    hmacHdrValLbl, hmacHeaderValue, hmacHint);
            setRowVisible(ntlm, ntlmDomainLbl, ntlmDomain, ntlmUserLbl, ntlmUsername,
                    ntlmPassLbl, ntlmPassword, ntlmWsLbl, ntlmWorkstation, ntlmHint);
        };
        authTypeCombo.valueProperty().addListener((o, ov, nv) -> refresh.run());
        refresh.run();

        return grid;
    }

    private static void setRowVisible(boolean visible, javafx.scene.Node... nodes) {
        for (javafx.scene.Node n : nodes) { n.setVisible(visible); n.setManaged(visible); }
    }

    /** Runs the interactive Authorization Code + PKCE flow; applies the token as a Bearer credential. */
    private void runAuthorizationCodeFlow() {
        OAuth2AuthCodeDialog dlg = new OAuth2AuthCodeDialog(
                oauthTokenUrl.getText(), oauthClientId.getText(), oauthClientSecret.getText(), oauthScope.getText());
        dlg.showAndWait().ifPresent(token -> {
            authTypeCombo.setValue(RestRequest.AuthType.BEARER);
            authToken.setText(token);
        });
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
        traceBox.setSelected(request.isTraceEnabled());
        traceBox.setTooltip(new Tooltip("Adds a W3C traceparent header and records a Zipkin span for each "
                + "request sent from this tab; export the trace from the URL bar"));

        Label ctLbl = new Label("Connect timeout (ms):");
        Label rtLbl = new Label("Read timeout (ms):");
        ctLbl.getStyleClass().add("meta-label");
        rtLbl.getStyleClass().add("meta-label");

        grid.add(ctLbl, 0, 0); grid.add(connectTimeoutField, 1, 0);
        grid.add(rtLbl, 0, 1); grid.add(readTimeoutField, 1, 1);
        grid.add(followRedirectsBox, 1, 2);
        grid.add(traceBox, 1, 3);

        // ---- TLS / mTLS ----
        Label tlsHeader = new Label("TLS / mTLS");
        tlsHeader.getStyleClass().add("sidebar-title");
        Label tlsHint = new Label("Point at a CA trust store to verify a private/self-signed server, "
                + "and a client key store (.p12/.jks) to present a client certificate for mutual TLS.");
        tlsHint.getStyleClass().add("meta-label");
        tlsHint.setWrapText(true);

        for (TextField f : new TextField[]{tlsTrustStore, tlsKeyStore}) { f.getStyleClass().add("nl-field"); f.setPrefWidth(300); }
        for (PasswordField f : new PasswordField[]{tlsTrustStorePw, tlsKeyStorePw}) { f.getStyleClass().add("nl-field"); f.setPrefWidth(160); }
        tlsTrustStore.setPromptText("CA trust store (.p12 / .jks) — verifies the server");
        tlsKeyStore.setPromptText("client key store (.p12 / .jks) — your client cert for mTLS");
        tlsTrustStorePw.setPromptText("password");
        tlsKeyStorePw.setPromptText("password");

        Label tsLbl = new Label("Trust store:");   tsLbl.getStyleClass().add("meta-label");
        Label ksLbl = new Label("Client key store:"); ksLbl.getStyleClass().add("meta-label");

        grid.add(tlsHeader, 0, 4);
        grid.add(tlsHint, 0, 5, 3, 1);
        grid.add(tsLbl, 0, 6); grid.add(new HBox(6, tlsTrustStore, browseStore(tlsTrustStore), tlsTrustStorePw), 1, 6, 2, 1);
        grid.add(ksLbl, 0, 7); grid.add(new HBox(6, tlsKeyStore, browseStore(tlsKeyStore), tlsKeyStorePw), 1, 7, 2, 1);
        grid.add(tlsTrustAll, 1, 8, 2, 1);
        return grid;
    }

    /** A "Browse…" button that fills {@code target} with a chosen keystore file path. */
    private Button browseStore(TextField target) {
        Button b = new Button("Browse…");
        b.getStyleClass().add("btn-secondary");
        b.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Choose keystore");
            fc.getExtensionFilters().addAll(
                    new javafx.stage.FileChooser.ExtensionFilter("Keystore", "*.p12", "*.pfx", "*.jks"),
                    new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"));
            java.io.File f = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) target.setText(f.getAbsolutePath());
        });
        return b;
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

        HBox meta = new HBox(16, statusLabel, timingLabel, sizeLabel, errorHelpLink);
        meta.setAlignment(Pos.CENTER_LEFT);
        meta.setPadding(new Insets(8, 10, 8, 10));

        responseBody = com.nexuslink.ui.util.JsonView.plainArea(false);

        bodyViewMode = new ComboBox<>(FXCollections.observableArrayList(BodyFormatter.Mode.values()));
        bodyViewMode.setValue(BodyFormatter.Mode.PRETTY);
        bodyViewMode.valueProperty().addListener((o, ov, nv) -> renderBody());
        Label viewLabel = new Label("View:");
        viewLabel.getStyleClass().add("meta-label");
        HBox bodyBar = new HBox(8, viewLabel, bodyViewMode);
        bodyBar.setAlignment(Pos.CENTER_LEFT);
        bodyBar.setPadding(new Insets(6, 6, 0, 6));
        org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> responseScroll =
                new org.fxmisc.flowless.VirtualizedScrollPane<>(responseBody);
        VBox bodyBox = new VBox(4, bodyBar, responseScroll);
        VBox.setVgrow(responseScroll, Priority.ALWAYS);

        responseHeaders = new TextArea();
        responseHeaders.setEditable(false);
        responseHeaders.getStyleClass().add("code-area");

        responseCookies = new TextArea();
        responseCookies.setEditable(false);
        responseCookies.getStyleClass().add("code-area");

        responseTests = new TextArea();
        responseTests.setEditable(false);
        responseTests.getStyleClass().add("code-area");
        responseTestsTab = new Tab("Test Results", responseTests);

        responseTimeline = new TimelineView();

        TabPane respTabs = new TabPane();
        respTabs.getStyleClass().add("editor-tabs");
        respTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        respTabs.getTabs().addAll(
                new Tab("Body", bodyBox),
                new Tab("Headers", responseHeaders),
                new Tab("Cookies", responseCookies),
                new Tab("Timeline", responseTimeline),
                new Tab("Trace", traceTree),
                responseTestsTab);

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

        EnvironmentService env = Env.service();

        // Pre-request script: compute values (timestamps, UUIDs, HMAC signatures, …) before sending.
        // Its ${NAME} references resolve against the active environment; the resulting vars then take
        // precedence over the environment when the request's own ${VAR} placeholders are resolved.
        Function<String, String> envResolver = env == null ? n -> null : env::resolve;
        PreRequestScript.Result pre = PreRequestScript.run(preRequestArea.getText(), envResolver);
        for (String err : pre.errors()) logger.accept("Pre-request script — " + err);
        if (pre.hasErrors()) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Pre-request script error (" + pre.errors().size() + ") — see log");
            return;     // don't send with a broken script; the log shows exactly what failed
        }
        if (!pre.variables().isEmpty()) {
            logger.accept("Pre-request set " + pre.variables().size() + " var(s): "
                    + String.join(", ", pre.variables().keySet()));
        }

        sendButton.setDisable(true);
        progress.setVisible(true);
        progress.setManaged(true);
        statusLabel.setText("Sending…");
        timingLabel.setText("");
        sizeLabel.setText("");
        errorHelpLink.showFor(null);   // clear any prior error affordance

        // Resolve ${VAR} references at send time: pre-request vars first, then the active environment.
        // The on-screen request stays templated (so history/replay re-resolve later); only this copy
        // carries real values. (A deep copy either way, safe to retain for HAR export.)
        UnaryOperator<String> interp = template -> VariableInterpolator.interpolate(template, name -> {
            String v = pre.variables().get(name);
            if (v != null) return v;
            return env == null ? null : env.resolve(name);
        });
        final RestRequest exec = request.interpolated(interp);
        final Instant startedAt = Instant.now();
        String loggedUrl = exec.effectiveUrl();
        if (env != null) loggedUrl = env.masker().scrub(loggedUrl);   // never log resolved secrets
        logger.accept(exec.getMethod() + " " + loggedUrl);

        Task<RestResponse> task = new Task<>() {
            @Override protected RestResponse call() {
                return executor.execute(exec);
            }
        };
        task.setOnSucceeded(e -> {
            RestResponse resp = task.getValue();
            harEntries.add(new HarExporter.Entry(exec, resp, startedAt));
            renderResponse(resp);
            finishSend();
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Error: " + task.getException());
            errorHelpLink.showFor(String.valueOf(task.getException()));
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
        request.setOauthTokenUrl(oauthTokenUrl.getText());
        request.setOauthClientId(oauthClientId.getText());
        request.setOauthClientSecret(oauthClientSecret.getText());
        request.setOauthScope(oauthScope.getText());
        request.setAwsRegion(awsRegion.getText());
        request.setAwsService(awsService.getText());
        request.setAwsAccessKey(awsAccessKey.getText());
        request.setAwsSecretKey(awsSecretKey.getText());
        request.setAwsSessionToken(awsSessionToken.getText());
        request.setHmacAlgorithm(hmacAlgorithm.getValue());
        request.setHmacEncoding(hmacEncoding.getValue());
        request.setHmacKeyId(hmacKeyId.getText());
        request.setHmacSecret(hmacSecret.getText());
        request.setHmacStringToSign(hmacStringToSign.getText());
        request.setHmacHeaderName(hmacHeaderName.getText());
        request.setHmacHeaderValue(hmacHeaderValue.getText());
        request.setNtlmDomain(ntlmDomain.getText());
        request.setNtlmUsername(ntlmUsername.getText());
        request.setNtlmPassword(ntlmPassword.getText());
        request.setNtlmWorkstation(ntlmWorkstation.getText());
        request.setTlsTrustStorePath(tlsTrustStore.getText());
        request.setTlsTrustStorePassword(tlsTrustStorePw.getText());
        request.setTlsKeyStorePath(tlsKeyStore.getText());
        request.setTlsKeyStorePassword(tlsKeyStorePw.getText());
        request.setTlsTrustAll(tlsTrustAll.isSelected());
        request.setConnectTimeoutMs(parseIntOr(connectTimeoutField.getText(), 10_000));
        request.setReadTimeoutMs(parseIntOr(readTimeoutField.getText(), 30_000));
        request.setFollowRedirects(followRedirectsBox.isSelected());
        request.setTraceEnabled(traceBox.isSelected());
        request.getAssertions().clear();
        request.getAssertions().addAll(assertionRows);
    }

    private void renderResponse(RestResponse resp) {
        lastResponse = resp;
        responseCookies.setText(formatCookies());
        renderTestResults(resp);
        responseTimeline.setTiming(resp.timing());
        traceTree.setSpans(executor.capturedSpans());
        if (resp.failed()) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("✖ " + resp.errorMessage());
            timingLabel.setText(resp.timing().totalMs() + " ms");
            com.nexuslink.ui.util.JsonView.setSmart(responseBody, "Request failed:\n\n" + resp.errorMessage()
                    + "\n\nPress F1 → Troubleshooting for common fixes.");
            responseHeaders.clear();
            logger.accept("FAILED — " + resp.errorMessage());
            com.nexuslink.ui.metrics.Metrics.record(
                    com.nexuslink.ui.util.EndpointLabel.forRest(request.getMethod(), request.getUrl()),
                    resp.timing().totalMs(), false, 0);
            recordHistory("✖ " + request.getMethod() + " " + request.getUrl()
                    + " — " + resp.errorMessage(), 0, resp.timing().totalMs());
            return;
        }

        statusLabel.getStyleClass().setAll("status-" + resp.statusClass() + "xx");
        statusLabel.setText(resp.statusCode() + " " + resp.statusText());
        timingLabel.setText(resp.timing().totalMs() + " ms  (ttfb "
                + resp.timing().ttfbMs() + " · dl " + resp.timing().downloadMs() + ")");
        sizeLabel.setText(resp.prettyBytes() + "  ·  " + resp.httpVersion());

        renderBody();
        responseHeaders.setText(formatHeaders(resp.headers()));
        logger.accept(resp.statusCode() + " " + resp.statusText()
                + "  " + resp.timing().totalMs() + "ms  " + resp.prettyBytes());
        com.nexuslink.ui.metrics.Metrics.record(
                com.nexuslink.ui.util.EndpointLabel.forRest(request.getMethod(), request.getUrl()),
                resp.timing().totalMs(), resp.statusClass() < 4, resp.bodyBytes());
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
        // OAuth: persist non-secret fields only (client secret is re-entered, like Basic password)
        root.put("oauthTokenUrl", request.getOauthTokenUrl());
        root.put("oauthClientId", request.getOauthClientId());
        root.put("oauthScope", request.getOauthScope());
        // AWS SigV4: persist non-secret fields only (secret/session keys are re-entered)
        root.put("awsRegion", request.getAwsRegion());
        root.put("awsService", request.getAwsService());
        root.put("awsAccessKey", request.getAwsAccessKey());
        // HMAC: persist everything but the secret (re-entered, like other secrets)
        root.put("hmacAlgorithm", request.getHmacAlgorithm().name());
        root.put("hmacEncoding", request.getHmacEncoding().name());
        root.put("hmacKeyId", request.getHmacKeyId());
        root.put("hmacStringToSign", request.getHmacStringToSign());
        root.put("hmacHeaderName", request.getHmacHeaderName());
        root.put("hmacHeaderValue", request.getHmacHeaderValue());
        // NTLM: persist non-secret fields only (password is re-entered, like other secrets)
        root.put("ntlmDomain", request.getNtlmDomain());
        root.put("ntlmUsername", request.getNtlmUsername());
        root.put("ntlmWorkstation", request.getNtlmWorkstation());
        // TLS: persist store paths + trust-all (passwords are re-entered, like other secrets)
        root.put("tlsTrustStorePath", request.getTlsTrustStorePath());
        root.put("tlsKeyStorePath", request.getTlsKeyStorePath());
        root.put("tlsTrustAll", request.isTlsTrustAll());
        root.put("preRequestScript", preRequestArea.getText());
        putKeyValues(root.putArray("params"), paramRows);
        putKeyValues(root.putArray("headers"), headerRows);
        ArrayNode tests = root.putArray("assertions");
        for (AssertionSpec a : assertionRows) {
            if (!a.isComplete()) continue;
            ObjectNode n = tests.addObject();
            n.put("enabled", a.isEnabled());
            n.put("type", a.getType().name());
            n.put("name", a.getName());
            n.put("target", a.getTarget());
            n.put("max", a.getMax());
        }
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
            oauthTokenUrl.setText(root.path("oauthTokenUrl").asText(""));
            oauthClientId.setText(root.path("oauthClientId").asText(""));
            oauthScope.setText(root.path("oauthScope").asText(""));
            awsRegion.setText(root.path("awsRegion").asText("us-east-1"));
            awsService.setText(root.path("awsService").asText("execute-api"));
            awsAccessKey.setText(root.path("awsAccessKey").asText(""));
            hmacAlgorithm.setValue(com.nexuslink.protocol.http.rest.HmacAuthenticator.Algorithm.valueOf(
                    root.path("hmacAlgorithm").asText("HMAC_SHA256")));
            hmacEncoding.setValue(com.nexuslink.protocol.http.rest.HmacAuthenticator.Encoding.valueOf(
                    root.path("hmacEncoding").asText("BASE64")));
            hmacKeyId.setText(root.path("hmacKeyId").asText(""));
            hmacStringToSign.setText(root.path("hmacStringToSign").asText("{method}\\n{path}\\n{date}"));
            hmacHeaderName.setText(root.path("hmacHeaderName").asText("Authorization"));
            hmacHeaderValue.setText(root.path("hmacHeaderValue").asText("HMAC {signature}"));
            ntlmDomain.setText(root.path("ntlmDomain").asText(""));
            ntlmUsername.setText(root.path("ntlmUsername").asText(""));
            ntlmWorkstation.setText(root.path("ntlmWorkstation").asText(""));
            tlsTrustStore.setText(root.path("tlsTrustStorePath").asText(""));
            tlsKeyStore.setText(root.path("tlsKeyStorePath").asText(""));
            tlsTrustAll.setSelected(root.path("tlsTrustAll").asBoolean(false));
            preRequestArea.setText(root.path("preRequestScript").asText(""));
            loadKeyValues(root.path("params"), paramRows);
            loadKeyValues(root.path("headers"), headerRows);
            loadAssertions(root.path("assertions"));
        } catch (Exception e) {
            logger.accept("Replay failed: " + e.getMessage());
        }
    }

    private void loadAssertions(com.fasterxml.jackson.databind.JsonNode arr) {
        assertionRows.clear();
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                AssertionSpec a = new AssertionSpec(
                        ResponseAssertions.Type.valueOf(
                                n.path("type").asText("STATUS_EQUALS")),
                        n.path("name").asText(""),
                        n.path("target").asText(""),
                        n.path("max").asText(""));
                a.setEnabled(n.path("enabled").asBoolean(true));
                assertionRows.add(a);
            });
        }
        assertionRows.add(new AssertionSpec()); // trailing empty row
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

    /** Renders the last successful response body in the selected view mode. */
    private void renderBody() {
        if (lastResponse == null || lastResponse.failed()) return;
        com.nexuslink.ui.util.JsonView.setSmart(responseBody, BodyFormatter.render(
                lastResponse.body(), contentTypeOf(lastResponse.headers()), bodyViewMode.getValue()));
    }

    private static String contentTypeOf(Map<String, List<String>> headers) {
        if (headers == null) return null;
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().equalsIgnoreCase("content-type"))
                .flatMap(e -> e.getValue().stream())
                .findFirst().orElse(null);
    }

    private String formatHeaders(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((k, vals) -> vals.forEach(v -> sb.append(k).append(": ").append(v).append('\n')));
        return sb.toString();
    }

    /** Evaluates the authored assertions against {@code resp} and renders the report. */
    private void renderTestResults(RestResponse resp) {
        ResponseAssertions assertions = AssertionSpec.toAssertions(request.getAssertions());
        if (assertions.assertions().isEmpty()) {
            responseTestsTab.setText("Test Results");
            responseTests.setText("No assertions defined. Add checks in the request's Tests tab.");
            return;
        }
        ResponseAssertions.Report report = assertions.evaluate(resp);
        responseTestsTab.setText("Test Results (" + report.summary() + ")");

        StringBuilder sb = new StringBuilder(report.summary()).append("\n\n");
        for (ResponseAssertions.Result r : report.results()) {
            sb.append(r.passed() ? "✔ PASS  " : "✘ FAIL  ")
              .append(r.assertion().label()).append('\n')
              .append("        ").append(r.message()).append("\n\n");
        }
        responseTests.setText(sb.toString());
        if (report.failedCount() > 0) {
            logger.accept("Assertions: " + report.summary());
        }
    }

    /** Renders the session cookie jar (after capturing this response's Set-Cookie headers). */
    private String formatCookies() {
        var cookies = executor.cookieJar().all();
        if (cookies.isEmpty()) {
            return "No cookies stored for this session.";
        }
        StringBuilder sb = new StringBuilder();
        for (var c : cookies) {
            sb.append(c.getName()).append(" = ").append(c.getValue()).append('\n');
            sb.append("    domain=").append(c.getDomain())
              .append("  path=").append(c.getPath());
            if (c.isSecure()) sb.append("  secure");
            if (c.isHostOnly()) sb.append("  host-only");
            sb.append("  expires=")
              .append(c.getExpiry() == null ? "session" : c.getExpiry());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private void seedExample() {
        urlField.setText("https://httpbin.org/get");
        headerRows.add(new RestRequest.KeyValue("Accept", "application/json"));
        headerRows.add(new RestRequest.KeyValue());
        paramRows.add(new RestRequest.KeyValue());
    }
}
