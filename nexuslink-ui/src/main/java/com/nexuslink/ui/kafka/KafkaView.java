package com.nexuslink.ui.kafka;

import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.protocol.kafka.ConsumerLagCalculator;
import com.nexuslink.protocol.kafka.KafkaExplorer;
import com.nexuslink.protocol.kafka.KafkaMessageExporter;
import com.nexuslink.protocol.kafka.KafkaMetricsSummary;
import com.nexuslink.protocol.kafka.KafkaService;
import com.nexuslink.protocol.kafka.MessageFilter;
import com.nexuslink.protocol.kafka.PayloadFormatter;
import com.nexuslink.protocol.kafka.SchemaRegistryClient;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Kafka client tab — connect to a broker (PLAINTEXT/SSL/SASL), browse the topic → partition tree,
 * produce records, and consume a live stream. Built on the Apache Kafka client.
 */
public final class KafkaView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final KafkaService service = new KafkaService();
    private final ResourceExplorerView explorer = new ResourceExplorerView("Topics");

    private final TextField bootstrapField = new TextField("localhost:9092");
    private final ComboBox<String> protocolCombo = new ComboBox<>();
    private final ComboBox<String> saslMechCombo = new ComboBox<>();
    private final TextField saslUser = new TextField();
    private final PasswordField saslPass = new PasswordField();
    private final TextField tlsTrustStore = new TextField();
    private final PasswordField tlsTrustStorePw = new PasswordField();
    private final TextField tlsKeyStore = new TextField();
    private final PasswordField tlsKeyStorePw = new PasswordField();
    private final CheckBox tlsSkipHostnameCheck = new CheckBox("Skip hostname check");
    private final Button connectBtn = new Button("Connect");
    private final Label statusLabel = new Label("Not connected");

    private final TextField produceTopic = new TextField();
    private final TextField produceKey = new TextField();
    private final TextArea produceValue = new TextArea();
    private final Label produceStatus = new Label();

    private final TextField consumeTopic = new TextField();
    private final TextField consumeGroup = new TextField();
    private final CheckBox fromBeginning = new CheckBox("From beginning");
    private final ToggleButton consumeToggle = new ToggleButton("Start consuming");
    private final TextArea consumeLog = new TextArea();
    private final ObservableList<KafkaService.KafkaMessage> consumedMessages = FXCollections.observableArrayList();
    private final FilteredList<KafkaService.KafkaMessage> filteredMessages = new FilteredList<>(consumedMessages);
    private final TableView<KafkaService.KafkaMessage> messageTable = new TableView<>(filteredMessages);
    private final ComboBox<PayloadFormatter.Format> formatCombo = new ComboBox<>();

    // Live message-browser filter (AND-combined key/value/partition predicates over the consumed list).
    private final TextField keyFilter = new TextField();
    private final TextField valueFilter = new TextField();
    private final TextField partitionFilter = new TextField();
    private final CheckBox regexFilter = new CheckBox("Regex");
    private final CheckBox caseSensitiveFilter = new CheckBox("Case");
    private final Label filterStatus = new Label();

    // Consumer-group lag monitor: pick/type a group, refresh the per-partition lag table, optionally poll.
    private final ComboBox<String> lagGroupCombo = new ComboBox<>();
    private final ObservableList<ConsumerLagCalculator.LagRow> lagRows = FXCollections.observableArrayList();
    private final TableView<ConsumerLagCalculator.LagRow> lagTable = new TableView<>(lagRows);
    private final CheckBox lagAutoRefresh = new CheckBox("Auto-refresh 5s");
    private final Label lagTotal = new Label("Total lag: 0");
    private final Label lagStatus = new Label();
    private final CheckBox lagShowChart = new CheckBox("Live chart");
    private final CheckBox lagShowHeatmap = new CheckBox("Heatmap");
    private final com.nexuslink.ui.chart.RollingLineChart lagChart =
            new com.nexuslink.ui.chart.RollingLineChart("Lag", 60);
    private final com.nexuslink.ui.chart.LagHeatmap lagHeatmap = new com.nexuslink.ui.chart.LagHeatmap();
    private Timeline lagTimeline;
    /** Guards against overlapping refreshes when a poll fires before the previous one finishes. */
    private boolean lagRefreshing = false;

    // Schema Registry browser: connect to a Confluent/Apicurio-compatible registry over HTTP (its own
    // URL + optional basic auth, independent of the broker), list subjects/versions, view + register schemas.
    private final TextField registryUrl = new TextField("http://localhost:8081");
    private final TextField registryUser = new TextField();
    private final PasswordField registryPass = new PasswordField();
    private final ObservableList<String> registrySubjects = FXCollections.observableArrayList();
    private final ListView<String> registrySubjectList = new ListView<>(registrySubjects);
    private final ComboBox<Integer> registryVersionCombo = new ComboBox<>();
    private final ComboBox<String> registryCompatCombo =
            new ComboBox<>(FXCollections.observableArrayList(SchemaRegistryClient.COMPATIBILITY_LEVELS));
    private final Label registryCompatLabel = new Label();
    private final Label registryStatus = new Label();
    private org.fxmisc.richtext.CodeArea registrySchemaArea;

    /** Cap on retained consumed messages so a long-running stream stays bounded. */
    private static final int MAX_MESSAGES = 10_000;

    private Consumer<String> logger = s -> {};

    public KafkaView() {
        getStyleClass().add("kafka-view");
        setTop(buildBar());
        setCenter(buildBody());
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
        explorer.setLogger(this.logger);
    }

    /** Pre-fills the bootstrap servers (used when opening a saved/sample connection). */
    public void prefill(String bootstrap) {
        if (bootstrap != null && !bootstrap.isBlank()) bootstrapField.setText(bootstrap);
    }

    private VBox buildBar() {
        bootstrapField.getStyleClass().add("nl-field");
        bootstrapField.setPromptText("host1:9092,host2:9092");
        HBox.setHgrow(bootstrapField, Priority.ALWAYS);

        protocolCombo.getItems().addAll("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
        protocolCombo.setValue("PLAINTEXT");
        saslMechCombo.getItems().addAll("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");
        saslMechCombo.setValue("PLAIN");
        saslUser.getStyleClass().add("nl-field");
        saslUser.setPromptText("SASL user");
        saslUser.setPrefWidth(140);
        saslPass.getStyleClass().add("nl-field");
        saslPass.setPromptText("SASL password");
        saslPass.setPrefWidth(140);

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> connect());

        Button diagnoseBtn = new Button("Diagnose");
        diagnoseBtn.getStyleClass().add("btn-secondary");
        diagnoseBtn.setTooltip(new Tooltip("Check reachability of the first broker (DNS → TCP)"));
        diagnoseBtn.setOnAction(e -> diagnose());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("kafka-client"));

        HBox row1 = new HBox(8, label("Brokers:"), bootstrapField, connectBtn, diagnoseBtn, helpBtn);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(10, 10, 4, 10));
        HBox row2 = new HBox(8, label("Security:"), protocolCombo, label("SASL:"), saslMechCombo, saslUser, saslPass);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 10, 6, 10));

        // TLS material row — relevant for SSL / SASL_SSL; shown only then.
        for (TextField f : new TextField[]{tlsTrustStore, tlsKeyStore}) { f.getStyleClass().add("nl-field"); f.setPrefWidth(180); }
        for (PasswordField f : new PasswordField[]{tlsTrustStorePw, tlsKeyStorePw}) { f.getStyleClass().add("nl-field"); f.setPrefWidth(120); }
        tlsTrustStore.setPromptText("trust store (.p12/.jks)");
        tlsKeyStore.setPromptText("client key store (.p12/.jks)");
        tlsTrustStorePw.setPromptText("password");
        tlsKeyStorePw.setPromptText("password");
        HBox tlsRow = new HBox(8, label("TLS:"), tlsTrustStore, browseStore(tlsTrustStore), tlsTrustStorePw,
                tlsKeyStore, browseStore(tlsKeyStore), tlsKeyStorePw, tlsSkipHostnameCheck);
        tlsRow.setAlignment(Pos.CENTER_LEFT);
        tlsRow.setPadding(new Insets(0, 10, 6, 10));
        tlsRow.visibleProperty().bind(protocolCombo.valueProperty().isEqualTo("SSL").or(protocolCombo.valueProperty().isEqualTo("SASL_SSL")));
        tlsRow.managedProperty().bind(tlsRow.visibleProperty());

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row1, row2, tlsRow, statusRow);
    }

    private Button browseStore(TextField target) {
        Button b = new Button("…");
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

    private SplitPane buildBody() {
        explorer.setMinWidth(220);
        explorer.setOnSelect(node -> {
            if (node.kind() == ResourceNode.Kind.TOPIC) {
                produceTopic.setText(node.label());
                consumeTopic.setText(node.label());
            }
        });

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("editor-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Produce", buildProduce()), new Tab("Consume", buildConsume()),
                new Tab("Consumer Lag", buildLag()), new Tab("Schema Registry", buildSchemaRegistry()));

        SplitPane sp = new SplitPane(explorer, tabs);
        sp.setDividerPositions(0.28);
        return sp;
    }

    private VBox buildProduce() {
        for (TextField f : new TextField[]{produceTopic, produceKey}) f.getStyleClass().add("nl-field");
        produceTopic.setPromptText("topic");
        produceKey.setPromptText("key (optional)");
        produceValue.getStyleClass().add("code-area");
        produceValue.setPromptText("record value");
        produceValue.setPrefRowCount(8);
        produceStatus.getStyleClass().add("meta-label");

        Button sendBtn = new Button("Send");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> produce());

        HBox top = new HBox(8, label("Topic:"), produceTopic, label("Key:"), produceKey, sendBtn, produceStatus);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, top, produceValue);
        box.setPadding(new Insets(8));
        VBox.setVgrow(produceValue, Priority.ALWAYS);
        return box;
    }

    private VBox buildConsume() {
        consumeTopic.getStyleClass().add("nl-field");
        consumeTopic.setPromptText("topic");
        consumeGroup.getStyleClass().add("nl-field");
        consumeGroup.setPromptText("group (optional)");
        consumeLog.getStyleClass().add("code-area");
        consumeLog.setEditable(false);
        consumeLog.setPrefRowCount(3);
        consumeLog.setPromptText("Status messages appear here…");
        consumeToggle.getStyleClass().add("btn-primary");
        consumeToggle.setOnAction(e -> toggleConsume());

        buildMessageTable();

        Button exportJson = new Button("Export JSON…");
        exportJson.getStyleClass().add("btn-secondary");
        exportJson.setOnAction(e -> exportMessages(true));
        Button exportCsv = new Button("Export CSV…");
        exportCsv.getStyleClass().add("btn-secondary");
        exportCsv.setOnAction(e -> exportMessages(false));
        Button browse = new Button("Browse 100");
        browse.getStyleClass().add("btn-secondary");
        browse.setTooltip(new Tooltip("Peek up to 100 messages with no consumer-group side effects (no commit/join)"));
        browse.setOnAction(e -> browseTopic());

        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> { consumedMessages.clear(); consumeLog.clear(); });

        formatCombo.getItems().addAll(PayloadFormatter.Format.values());
        formatCombo.setValue(PayloadFormatter.Format.STRING);
        // Re-render key/value cells through the newly selected formatter (raw data is untouched).
        formatCombo.valueProperty().addListener((o, a, b) -> messageTable.refresh());

        HBox top = new HBox(8, label("Topic:"), consumeTopic, label("Group:"), consumeGroup,
                fromBeginning, consumeToggle, browse, label("Format:"), formatCombo, exportJson, exportCsv, clear);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, top, buildFilterBar(), messageTable, consumeLog);
        box.setPadding(new Insets(8));
        VBox.setVgrow(messageTable, Priority.ALWAYS);
        return box;
    }

    /**
     * Consumer-group lag monitor — load the broker's consumer groups, pick (or type) one, and
     * refresh a per-partition committed/end-offset/lag table. An optional 5-second auto-refresh
     * polls without stacking overlapping calls.
     */
    private VBox buildLag() {
        lagGroupCombo.setEditable(true);
        lagGroupCombo.getStyleClass().add("nl-field");
        lagGroupCombo.setPromptText("consumer group");
        lagGroupCombo.setPrefWidth(220);

        Button loadGroups = new Button("Load groups");
        loadGroups.getStyleClass().add("btn-secondary");
        loadGroups.setOnAction(e -> loadGroups());

        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("btn-primary");
        refresh.setOnAction(e -> refreshLag());

        Button reset = new Button("Reset offsets…");
        reset.getStyleClass().add("btn-secondary");
        reset.setTooltip(new Tooltip("Reset this group's committed offsets (earliest/latest/specific/timestamp/shift)"));
        reset.setOnAction(e -> resetOffsets());

        Button metrics = new Button("Metrics…");
        metrics.getStyleClass().add("btn-secondary");
        metrics.setTooltip(new Tooltip("Show live AdminClient metrics (connections, request/response rates, throughput)"));
        metrics.setOnAction(e -> showMetrics());

        lagAutoRefresh.setOnAction(e -> toggleLagAutoRefresh());
        lagTotal.getStyleClass().add("meta-label");
        lagStatus.getStyleClass().add("meta-label");

        buildLagTable();

        // Live per-partition lag chart: hidden by default, toggled by the "Live chart" checkbox. Each
        // refresh appends a point per partition (see recordLagChart) so trends/spikes are visible.
        lagChart.setPrefHeight(220);
        lagChart.setMinHeight(160);
        lagChart.managedProperty().bind(lagChart.visibleProperty());
        lagChart.setVisible(false);
        lagShowChart.setOnAction(e -> {
            lagChart.setVisible(lagShowChart.isSelected());
            if (!lagShowChart.isSelected()) lagChart.reset();
        });

        lagHeatmap.setPrefHeight(200);
        lagHeatmap.setMinHeight(140);
        lagHeatmap.managedProperty().bind(lagHeatmap.visibleProperty());
        lagHeatmap.setVisible(false);
        lagShowHeatmap.setOnAction(e -> {
            lagHeatmap.setVisible(lagShowHeatmap.isSelected());
            if (lagShowHeatmap.isSelected()) lagHeatmap.setData(lagRows);
        });

        HBox top = new HBox(8, label("Group:"), lagGroupCombo, loadGroups, refresh, reset, metrics,
                lagAutoRefresh, lagShowChart, lagShowHeatmap, lagTotal, lagStatus);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, top, lagTable, lagChart, lagHeatmap);
        box.setPadding(new Insets(8));
        VBox.setVgrow(lagTable, Priority.ALWAYS);
        return box;
    }

    /** Appends the current per-partition lag to the live chart (one series per topic-partition). */
    private void recordLagChart(List<ConsumerLagCalculator.LagRow> rows) {
        if (!lagShowChart.isSelected()) return;
        java.util.Map<String, Long> byPartition = new java.util.LinkedHashMap<>();
        for (ConsumerLagCalculator.LagRow r : rows) {
            byPartition.put(r.topic() + "-" + r.partition(), r.lag());
        }
        lagChart.tick(byPartition);
    }

    /**
     * Confluent/Apicurio-compatible Schema Registry browser. Independent of the Kafka broker
     * connection: point it at the registry's HTTP URL (optionally with basic auth), list subjects,
     * pick a version to view its schema, or register a new one. Every call runs off the FX thread
     * against a fresh {@link SchemaRegistryClient} built from the current fields.
     */
    private VBox buildSchemaRegistry() {
        for (TextField f : new TextField[]{registryUrl, registryUser, registryPass}) f.getStyleClass().add("nl-field");
        registryUrl.setPromptText("http://localhost:8081");
        registryUrl.setPrefWidth(240);
        registryUser.setPromptText("user (optional)");
        registryUser.setPrefWidth(120);
        registryPass.setPromptText("password");
        registryPass.setPrefWidth(120);
        registryStatus.getStyleClass().add("meta-label");

        Button loadBtn = new Button("Load subjects");
        loadBtn.getStyleClass().add("btn-primary");
        loadBtn.setOnAction(e -> loadSubjects());

        Button registerBtn = new Button("Register…");
        registerBtn.getStyleClass().add("btn-secondary");
        registerBtn.setOnAction(e -> registerSchema());

        registrySubjectList.getSelectionModel().selectedItemProperty().addListener(
                (o, a, subject) -> { if (subject != null) { loadVersions(subject); loadCompatibility(subject); } });
        registryVersionCombo.setPromptText("version");
        registryVersionCombo.valueProperty().addListener((o, a, v) -> {
            String subject = registrySubjectList.getSelectionModel().getSelectedItem();
            if (subject != null && v != null) loadSchema(subject, v);
        });

        registrySchemaArea = com.nexuslink.ui.util.JsonView.plainArea(false);
        org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.CodeArea> schemaScroll =
                new org.fxmisc.flowless.VirtualizedScrollPane<>(registrySchemaArea);

        HBox top = new HBox(8, label("Registry:"), registryUrl, label("User:"), registryUser,
                registryPass, loadBtn, registerBtn, registryStatus);
        top.setAlignment(Pos.CENTER_LEFT);

        registryCompatLabel.getStyleClass().add("meta-label");
        Button setCompatBtn = new Button("Set");
        setCompatBtn.getStyleClass().add("btn-secondary");
        setCompatBtn.setTooltip(new Tooltip("Set this subject's compatibility level (an override on the global default)"));
        setCompatBtn.setOnAction(e -> setCompatibility());
        HBox compatRow = new HBox(8, label("Compatibility:"), registryCompatCombo, setCompatBtn, registryCompatLabel);
        compatRow.setAlignment(Pos.CENTER_LEFT);

        VBox versionBox = new VBox(6, label("Version:"), registryVersionCombo, compatRow, schemaScroll);
        versionBox.setPadding(new Insets(0, 0, 0, 8));
        VBox.setVgrow(schemaScroll, Priority.ALWAYS);
        SplitPane sp = new SplitPane(registrySubjectList, versionBox);
        sp.setDividerPositions(0.30);

        VBox box = new VBox(8, top, sp);
        box.setPadding(new Insets(8));
        VBox.setVgrow(sp, Priority.ALWAYS);
        return box;
    }

    /** A client built from the current registry URL plus optional basic-auth fields. */
    private SchemaRegistryClient registryClient() {
        String url = registryUrl.getText().trim();
        String user = registryUser.getText();
        return user != null && !user.isBlank()
                ? new SchemaRegistryClient(url, user, registryPass.getText())
                : new SchemaRegistryClient(url);
    }

    private void loadSubjects() {
        if (registryUrl.getText() == null || registryUrl.getText().isBlank()) {
            registryStatus.getStyleClass().setAll("status-err");
            registryStatus.setText("Enter a registry URL");
            return;
        }
        registryStatus.getStyleClass().setAll("meta-label");
        registryStatus.setText("Loading subjects…");
        SchemaRegistryClient client = registryClient();
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception { return client.listSubjects(); }
        };
        task.setOnSucceeded(e -> {
            registrySubjects.setAll(task.getValue());
            registryVersionCombo.getItems().clear();
            registrySchemaArea.clear();
            registryStatus.getStyleClass().setAll("meta-label");
            registryStatus.setText(task.getValue().size() + " subject(s)");
            logger.accept("Schema Registry: loaded " + task.getValue().size() + " subject(s)");
        });
        task.setOnFailed(e -> registryFail("load subjects", task.getException()));
        runBg(task, "schema-subjects");
    }

    private void loadVersions(String subject) {
        SchemaRegistryClient client = registryClient();
        Task<List<Integer>> task = new Task<>() {
            @Override protected List<Integer> call() throws Exception { return client.listVersions(subject); }
        };
        task.setOnSucceeded(e -> {
            List<Integer> versions = task.getValue();
            registryVersionCombo.getItems().setAll(versions);
            // Select the latest version, which loads its schema via the value listener.
            if (!versions.isEmpty()) registryVersionCombo.setValue(versions.get(versions.size() - 1));
        });
        task.setOnFailed(e -> registryFail("load versions", task.getException()));
        runBg(task, "schema-versions");
    }

    /**
     * Loads {@code subject}'s effective compatibility level into the combo: its own override if set,
     * otherwise the global default (shown as "inherited"). Runs off the FX thread.
     */
    private void loadCompatibility(String subject) {
        SchemaRegistryClient client = registryClient();
        registryCompatLabel.setText("");
        Task<String[]> task = new Task<>() {
            @Override protected String[] call() throws Exception {
                String subjectLevel = client.getSubjectCompatibility(subject);
                // {level, "override"|"inherited"} — fall back to the global default when unset.
                return subjectLevel != null
                        ? new String[]{subjectLevel, "override"}
                        : new String[]{client.getGlobalCompatibility(), "inherited"};
            }
        };
        task.setOnSucceeded(e -> {
            String[] r = task.getValue();
            registryCompatCombo.setValue(r[0]);
            registryCompatLabel.setText("(" + r[1] + ")");
        });
        task.setOnFailed(e -> registryCompatLabel.setText("compatibility unavailable"));
        runBg(task, "schema-compat");
    }

    /** Sets the selected subject's compatibility override to the combo value, off the FX thread. */
    private void setCompatibility() {
        String subject = registrySubjectList.getSelectionModel().getSelectedItem();
        String level = registryCompatCombo.getValue();
        if (subject == null || level == null) return;
        SchemaRegistryClient client = registryClient();
        registryStatus.getStyleClass().setAll("meta-label");
        registryStatus.setText("Setting compatibility…");
        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception { return client.setSubjectCompatibility(subject, level); }
        };
        task.setOnSucceeded(e -> {
            registryCompatLabel.setText("(override)");
            registryStatus.setText(subject + " compatibility → " + task.getValue());
            logger.accept("Schema Registry: " + subject + " compatibility set to " + task.getValue());
        });
        task.setOnFailed(e -> registryFail("set compatibility", task.getException()));
        runBg(task, "schema-set-compat");
    }

    private void loadSchema(String subject, int version) {
        SchemaRegistryClient client = registryClient();
        Task<SchemaRegistryClient.Schema> task = new Task<>() {
            @Override protected SchemaRegistryClient.Schema call() throws Exception {
                return client.getSchema(subject, version);
            }
        };
        task.setOnSucceeded(e -> {
            SchemaRegistryClient.Schema s = task.getValue();
            com.nexuslink.ui.util.JsonView.setSmart(registrySchemaArea, s.schema());
            registryStatus.getStyleClass().setAll("meta-label");
            registryStatus.setText(subject + " v" + s.version() + " (id " + s.id() + ")");
        });
        task.setOnFailed(e -> registryFail("load schema", task.getException()));
        runBg(task, "schema-get");
    }

    /** Prompts for a subject + schema and registers it, refreshing the subject list on success. */
    private void registerSchema() {
        if (registryUrl.getText() == null || registryUrl.getText().isBlank()) {
            registryStatus.getStyleClass().setAll("status-err");
            registryStatus.setText("Enter a registry URL");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Register schema");
        dialog.setHeaderText("Register a new schema version under a subject");
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });
        TextField subjectField = new TextField(registrySubjectList.getSelectionModel().getSelectedItem());
        subjectField.setPromptText("subject");
        TextArea schemaField = new TextArea();
        schemaField.getStyleClass().add("code-area");
        schemaField.setPromptText("{\"type\":\"record\",\"name\":\"...\",\"fields\":[...]}");
        schemaField.setPrefRowCount(10);
        schemaField.setPrefColumnCount(50);
        VBox content = new VBox(8, label("Subject:"), subjectField, label("Schema:"), schemaField);
        VBox.setVgrow(schemaField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        String subject = subjectField.getText() == null ? "" : subjectField.getText().trim();
        String schema = schemaField.getText();
        if (subject.isBlank() || schema == null || schema.isBlank()) {
            registryStatus.getStyleClass().setAll("status-err");
            registryStatus.setText("Subject and schema are required");
            return;
        }
        registryStatus.getStyleClass().setAll("meta-label");
        registryStatus.setText("Registering…");
        SchemaRegistryClient client = registryClient();
        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception { return client.register(subject, schema); }
        };
        task.setOnSucceeded(e -> {
            registryStatus.setText("Registered " + subject + " → id " + task.getValue());
            logger.accept("Schema Registry: registered " + subject + " (id " + task.getValue() + ")");
            loadSubjects();
        });
        task.setOnFailed(e -> registryFail("register", task.getException()));
        runBg(task, "schema-register");
    }

    private void registryFail(String what, Throwable ex) {
        registryStatus.getStyleClass().setAll("status-err");
        registryStatus.setText("✖ " + ex.getMessage());
        logger.accept("Schema Registry: " + what + " FAILED: " + ex.getMessage());
    }

    private void buildLagTable() {
        lagTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lagTable.setPlaceholder(new Label("Pick a consumer group and Refresh to see per-partition lag."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(lagTable);

        TableColumn<ConsumerLagCalculator.LagRow, String> topic = new TableColumn<>("Topic");
        topic.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().topic()));

        TableColumn<ConsumerLagCalculator.LagRow, Number> part = new TableColumn<>("Partition");
        part.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().partition()));
        part.setMaxWidth(110);
        rightAlign(part);

        TableColumn<ConsumerLagCalculator.LagRow, Number> committed = new TableColumn<>("Committed");
        committed.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().committed()));
        rightAlign(committed);

        TableColumn<ConsumerLagCalculator.LagRow, Number> end = new TableColumn<>("End offset");
        end.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().endOffset()));
        rightAlign(end);

        TableColumn<ConsumerLagCalculator.LagRow, Number> lag = new TableColumn<>("Lag");
        lag.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().lag()));
        rightAlign(lag);

        lagTable.getColumns().addAll(List.of(topic, part, committed, end, lag));
    }

    /** Right-aligns a numeric column's cell content. */
    private static <S> void rightAlign(TableColumn<S, Number> col) {
        col.setStyle("-fx-alignment: CENTER-RIGHT;");
    }

    /** Loads the broker's consumer groups into the combo (off the FX thread). */
    private void loadGroups() {
        lagStatus.getStyleClass().setAll("meta-label");
        lagStatus.setText("Loading groups…");
        Task<List<String>> task = new Task<>() {
            @Override protected List<String> call() throws Exception {
                return service.listConsumerGroups();
            }
        };
        task.setOnSucceeded(e -> {
            List<String> groups = task.getValue();
            String current = lagGroupCombo.getEditor().getText();
            lagGroupCombo.getItems().setAll(groups);
            if (current != null && !current.isBlank()) lagGroupCombo.getEditor().setText(current);
            lagStatus.setText(groups.size() + " group(s)");
            logger.accept("Kafka lag: loaded " + groups.size() + " consumer group(s)");
        });
        task.setOnFailed(e -> {
            lagStatus.getStyleClass().setAll("status-err");
            lagStatus.setText("✖ " + task.getException().getMessage());
            logger.accept("Kafka lag: load groups FAILED: " + task.getException().getMessage());
        });
        runBg(task, "kafka-lag-groups");
    }

    /** Reads the currently selected/typed group name from the editable combo. */
    private String currentLagGroup() {
        String typed = lagGroupCombo.getEditor().getText();
        if (typed != null && !typed.isBlank()) return Env.resolve(typed.trim());
        String value = lagGroupCombo.getValue();
        return value == null ? "" : Env.resolve(value.trim());
    }

    /** Refreshes the lag table for the current group (off the FX thread; skips if one is in flight). */
    private void refreshLag() {
        String group = currentLagGroup();
        if (group.isEmpty()) {
            lagStatus.getStyleClass().setAll("status-err");
            lagStatus.setText("Enter a consumer group");
            if (lagAutoRefresh.isSelected()) { lagAutoRefresh.setSelected(false); stopLagTimeline(); }
            return;
        }
        if (lagRefreshing) return;   // a previous refresh is still running — don't stack
        lagRefreshing = true;
        lagStatus.getStyleClass().setAll("meta-label");
        lagStatus.setText("Refreshing…");
        Task<List<ConsumerLagCalculator.LagRow>> task = new Task<>() {
            @Override protected List<ConsumerLagCalculator.LagRow> call() throws Exception {
                return service.consumerGroupLag(group);
            }
        };
        task.setOnSucceeded(e -> {
            lagRefreshing = false;
            List<ConsumerLagCalculator.LagRow> rows = task.getValue();
            lagRows.setAll(rows);
            lagTotal.setText("Total lag: " + ConsumerLagCalculator.totalLag(rows));
            lagStatus.setText(rows.size() + " partition(s) · " + LocalTime.now().format(TIME));
            recordLagChart(rows);
            if (lagShowHeatmap.isSelected()) lagHeatmap.setData(rows);
        });
        task.setOnFailed(e -> {
            lagRefreshing = false;
            lagStatus.getStyleClass().setAll("status-err");
            lagStatus.setText("✖ " + task.getException().getMessage());
            logger.accept("Kafka lag: refresh FAILED: " + task.getException().getMessage());
            // Stop polling on failure so we don't spam a broken connection every 5s.
            if (lagAutoRefresh.isSelected()) { lagAutoRefresh.setSelected(false); stopLagTimeline(); }
        });
        runBg(task, "kafka-lag-refresh");
    }

    /**
     * Opens the {@link OffsetResetDialog} for the current group. The dialog previews via
     * {@link com.nexuslink.protocol.kafka.KafkaService#previewOffsetReset} and commits via
     * {@code applyOffsetReset}, both off the FX thread; on success it refreshes the lag table.
     */
    private void resetOffsets() {
        String group = currentLagGroup();
        if (group.isEmpty()) {
            lagStatus.getStyleClass().setAll("status-err");
            lagStatus.setText("Enter a consumer group");
            return;
        }
        Window owner = getScene() == null ? null : getScene().getWindow();
        new OffsetResetDialog(owner, group,
                (strategy, arg, ts) -> service.previewOffsetReset(group, strategy, arg, ts),
                rows -> service.applyOffsetReset(group, rows),
                this::refreshLag).show();
    }

    /**
     * Opens a live metrics dialog: the curated {@link KafkaMetricsSummary} table plus a rolling
     * throughput chart (incoming/outgoing bytes/s) and a msgs/s + partition-count line, all polled
     * every 2 s off the FX thread from the broker client's AdminClient metrics. Needs a connection.
     */
    private void showMetrics() {
        if (!service.isConnected()) {
            lagStatus.getStyleClass().setAll("status-err");
            lagStatus.setText("✖ metrics: not connected");
            return;
        }
        ObservableList<KafkaMetricsSummary.Metric> metricRows = FXCollections.observableArrayList();
        TableView<KafkaMetricsSummary.Metric> tv = new TableView<>(metricRows);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        tv.setPlaceholder(new Label("No metrics available"));
        TableColumn<KafkaMetricsSummary.Metric, String> mName = new TableColumn<>("Metric");
        mName.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().label()));
        TableColumn<KafkaMetricsSummary.Metric, String> mVal = new TableColumn<>("Value");
        mVal.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().value()));
        mVal.setStyle("-fx-alignment: CENTER-RIGHT;");
        tv.getColumns().add(mName);
        tv.getColumns().add(mVal);
        tv.setPrefSize(380, 200);

        com.nexuslink.ui.chart.RollingLineChart throughput =
                new com.nexuslink.ui.chart.RollingLineChart("bytes/s", 60);
        throughput.setPrefHeight(200);
        Label rateLabel = new Label("—");
        rateLabel.getStyleClass().add("meta-label");
        Label chartTitle = new Label("Throughput (bytes/s)");
        chartTitle.getStyleClass().add("conn-section-header");

        VBox content = new VBox(8, tv, chartTitle, throughput, rateLabel);
        content.setPadding(new Insets(4));
        content.setPrefWidth(460);
        VBox.setVgrow(throughput, Priority.ALWAYS);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Kafka metrics");
        dialog.setHeaderText("Live AdminClient metrics (2 s poll)");
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) com.nexuslink.ui.theme.ThemeManager.get().register(sc);
        });

        Timeline poll = new Timeline(new KeyFrame(Duration.seconds(2),
                e -> pollMetricsOnce(metricRows, throughput, rateLabel)));
        poll.setCycleCount(Timeline.INDEFINITE);
        dialog.setOnShown(e -> { pollMetricsOnce(metricRows, throughput, rateLabel); poll.play(); });
        dialog.setOnHidden(e -> poll.stop());
        dialog.show();
    }

    /** One metrics poll: fetch off-FX, then update the table, throughput chart and rate line. */
    private void pollMetricsOnce(ObservableList<KafkaMetricsSummary.Metric> rows,
                                 com.nexuslink.ui.chart.RollingLineChart throughput, Label rateLabel) {
        Task<Map<String, Double>> task = new Task<>() {
            @Override protected Map<String, Double> call() { return service.metricValues(); }
        };
        task.setOnSucceeded(e -> {
            Map<String, Double> raw = task.getValue();
            rows.setAll(KafkaMetricsSummary.summarize(raw));
            double in = numeric(raw.get("incoming-byte-rate"));
            double out = numeric(raw.get("outgoing-byte-rate"));
            Map<String, Number> point = new java.util.LinkedHashMap<>();
            point.put("in", in);
            point.put("out", out);
            throughput.tick(point);
            double reqRate = numeric(raw.get("request-rate")) + numeric(raw.get("response-rate"));
            rateLabel.setText(String.format("%.1f msgs/s (req+resp) · in %s · out %s · %d partition(s) tracked",
                    reqRate, KafkaMetricsSummary.humanRate(in), KafkaMetricsSummary.humanRate(out), lagRows.size()));
        });
        task.setOnFailed(e -> rateLabel.setText("✖ " + task.getException().getMessage()));
        runBg(task, "kafka-metrics");
    }

    private static double numeric(Double d) {
        return (d == null || d.isNaN()) ? 0.0 : d;
    }

    /** Starts or stops the 5-second auto-refresh poll based on the checkbox state. */
    private void toggleLagAutoRefresh() {
        if (lagAutoRefresh.isSelected()) {
            if (currentLagGroup().isEmpty()) {
                lagAutoRefresh.setSelected(false);
                lagStatus.getStyleClass().setAll("status-err");
                lagStatus.setText("Enter a consumer group");
                return;
            }
            stopLagTimeline();
            lagTimeline = new Timeline(new KeyFrame(Duration.seconds(5), e -> refreshLag()));
            lagTimeline.setCycleCount(Animation.INDEFINITE);
            lagTimeline.play();
            refreshLag();   // immediate first refresh, then every 5s
        } else {
            stopLagTimeline();
        }
    }

    /** Stops and discards the poll timer so it doesn't leak or keep firing. */
    private void stopLagTimeline() {
        if (lagTimeline != null) {
            lagTimeline.stop();
            lagTimeline = null;
        }
    }

    /** A live filter bar over the consumed-message table, backed by {@link MessageFilter}. */
    private HBox buildFilterBar() {
        keyFilter.getStyleClass().add("nl-field");
        keyFilter.setPromptText("key contains…");
        keyFilter.setPrefWidth(150);
        valueFilter.getStyleClass().add("nl-field");
        valueFilter.setPromptText("value contains…");
        valueFilter.setPrefWidth(180);
        partitionFilter.getStyleClass().add("nl-field");
        partitionFilter.setPromptText("part.");
        partitionFilter.setPrefWidth(60);
        filterStatus.getStyleClass().add("meta-label");

        // Re-apply whenever any control changes so the table filters as you type.
        keyFilter.textProperty().addListener((o, a, b) -> applyFilter());
        valueFilter.textProperty().addListener((o, a, b) -> applyFilter());
        partitionFilter.textProperty().addListener((o, a, b) -> applyFilter());
        regexFilter.selectedProperty().addListener((o, a, b) -> applyFilter());
        caseSensitiveFilter.selectedProperty().addListener((o, a, b) -> applyFilter());

        Button clearFilter = new Button("Clear filter");
        clearFilter.getStyleClass().add("btn-secondary");
        clearFilter.setOnAction(e -> {
            keyFilter.clear();
            valueFilter.clear();
            partitionFilter.clear();
            regexFilter.setSelected(false);
            caseSensitiveFilter.setSelected(false);
        });

        HBox bar = new HBox(8, label("Filter — Key:"), keyFilter, label("Value:"), valueFilter,
                label("Partition:"), partitionFilter, regexFilter, caseSensitiveFilter, clearFilter, filterStatus);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /**
     * Rebuilds the {@link MessageFilter} from the filter bar and applies it to the table's
     * {@link FilteredList}. An empty bar shows everything; an invalid regex is reported inline
     * (and leaves the previous predicate in place rather than throwing).
     */
    private void applyFilter() {
        String key = keyFilter.getText() == null ? "" : keyFilter.getText().trim();
        String value = valueFilter.getText() == null ? "" : valueFilter.getText().trim();
        String partText = partitionFilter.getText() == null ? "" : partitionFilter.getText().trim();
        boolean regex = regexFilter.isSelected();
        boolean cs = caseSensitiveFilter.isSelected();

        MessageFilter.Builder b = MessageFilter.builder();
        try {
            if (!key.isEmpty()) {
                if (regex) b.keyMatches(key, cs); else b.keyContains(key, cs);
            }
            if (!value.isEmpty()) {
                if (regex) b.valueMatches(value, cs); else b.valueContains(value, cs);
            }
            if (!partText.isEmpty()) b.partition(Integer.parseInt(partText));
        } catch (java.util.regex.PatternSyntaxException ex) {
            filterStatus.setText("✖ invalid regex");
            return;
        } catch (NumberFormatException ex) {
            filterStatus.setText("✖ partition must be a number");
            return;
        }

        MessageFilter filter = b.build();
        filteredMessages.setPredicate(m -> filter.matches(
                new MessageFilter.Record(m.partition(), m.offset(), m.timestamp(), m.key(), m.value(), Map.of())));
        boolean active = !key.isEmpty() || !value.isEmpty() || !partText.isEmpty();
        filterStatus.setText(active
                ? "showing " + filteredMessages.size() + " of " + consumedMessages.size()
                : "");
    }

    private void buildMessageTable() {
        messageTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        messageTable.setPlaceholder(new Label("Consumed records appear here. Start consuming to populate."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(messageTable);

        TableColumn<KafkaService.KafkaMessage, String> time = new TableColumn<>("Time");
        time.setCellValueFactory(c -> new SimpleStringProperty(Instant.ofEpochMilli(c.getValue().timestamp())
                .atZone(ZoneId.systemDefault()).toLocalTime().format(TIME)));
        time.setMaxWidth(120);
        TableColumn<KafkaService.KafkaMessage, Number> part = new TableColumn<>("Partition");
        part.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().partition()));
        part.setMaxWidth(90);
        TableColumn<KafkaService.KafkaMessage, Number> off = new TableColumn<>("Offset");
        off.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().offset()));
        off.setMaxWidth(110);
        TableColumn<KafkaService.KafkaMessage, String> key = new TableColumn<>("Key");
        key.setCellValueFactory(c -> new SimpleStringProperty(
                PayloadFormatter.format(c.getValue().key(), formatCombo.getValue())));
        key.setMaxWidth(160);
        TableColumn<KafkaService.KafkaMessage, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(c -> new SimpleStringProperty(
                PayloadFormatter.format(c.getValue().value(), formatCombo.getValue())));

        messageTable.getColumns().addAll(List.of(time, part, off, key, value));
    }

    /** Exports selected rows (or all when none selected) to a JSON or CSV file. */
    private void exportMessages(boolean asJson) {
        var selected = messageTable.getSelectionModel().getSelectedItems();
        List<KafkaService.KafkaMessage> toExport = selected.isEmpty()
                ? List.copyOf(consumedMessages) : List.copyOf(selected);
        if (toExport.isEmpty()) { logger.accept("Kafka export: no messages to export."); return; }

        FileChooser fc = new FileChooser();
        fc.setTitle("Export consumed messages");
        String ext = asJson ? "json" : "csv";
        fc.setInitialFileName("kafka-messages." + ext);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext.toUpperCase() + " files", "*." + ext));
        File file = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) return;

        String content = asJson
                ? KafkaMessageExporter.toJson(toExport) : KafkaMessageExporter.toCsv(toExport);
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            logger.accept("Kafka export: wrote " + toExport.size() + " message(s) to " + file.getName());
        } catch (Exception ex) {
            logger.accept("Kafka export FAILED: " + ex.getMessage());
        }
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private Map<String, String> securityProps() {
        Map<String, String> props = new LinkedHashMap<>();
        String protocol = protocolCombo.getValue();
        if (!"PLAINTEXT".equals(protocol)) props.put("security.protocol", protocol);
        if (protocol.startsWith("SASL")) {
            String mech = saslMechCombo.getValue();
            props.put("sasl.mechanism", mech);
            String module = mech.startsWith("SCRAM")
                    ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule";
            props.put("sasl.jaas.config", module + " required username=\"" + Env.resolve(saslUser.getText())
                    + "\" password=\"" + Env.resolve(saslPass.getText()) + "\";");
        }
        // TLS material (applies to SSL / SASL_SSL): CA trust store + optional client key store (mTLS).
        if (protocol.endsWith("SSL")) {
            String ts = Env.resolve(tlsTrustStore.getText().trim());
            if (!ts.isBlank()) {
                props.put("ssl.truststore.location", ts);
                props.put("ssl.truststore.type", storeType(ts));
                if (!tlsTrustStorePw.getText().isEmpty()) props.put("ssl.truststore.password", tlsTrustStorePw.getText());
            }
            String ks = Env.resolve(tlsKeyStore.getText().trim());
            if (!ks.isBlank()) {
                props.put("ssl.keystore.location", ks);
                props.put("ssl.keystore.type", storeType(ks));
                if (!tlsKeyStorePw.getText().isEmpty()) {
                    props.put("ssl.keystore.password", tlsKeyStorePw.getText());
                    props.put("ssl.key.password", tlsKeyStorePw.getText());
                }
            }
            if (tlsSkipHostnameCheck.isSelected()) props.put("ssl.endpoint.identification.algorithm", "");
        }
        return props;
    }

    /** Kafka keystore type from a file extension ({@code .jks} → JKS, else PKCS12). */
    private static String storeType(String path) {
        return path.toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";
    }

    /** Runs a DNS→TCP reachability check against the first broker in the bootstrap list. */
    private void diagnose() {
        String bootstrap = Env.resolve(bootstrapField.getText().trim());
        String first = bootstrap.split(",")[0].trim();
        if (first.isEmpty()) { statusLabel.setText("Enter a broker host:port first"); return; }
        int colon = first.lastIndexOf(':');
        String host = colon > 0 ? first.substring(0, colon) : first;
        int port = 9092;
        if (colon > 0) { try { port = Integer.parseInt(first.substring(colon + 1)); } catch (NumberFormatException ignored) {} }
        Window owner = getScene() == null ? null : getScene().getWindow();
        com.nexuslink.ui.diagnostics.DiagnosticsDialog.run(owner, "Broker " + host + ":" + port,
                com.nexuslink.core.diagnostics.NetworkProbes.basicSteps(host, port, false, 3000));
    }

    private void connect() {
        String bootstrap = Env.resolve(bootstrapField.getText().trim());   // resolve ${VAR} against active environment
        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Connecting…");
        logger.accept("Kafka connect → " + bootstrap);
        Map<String, String> security = securityProps();

        Task<Integer> task = new Task<>() {
            @Override protected Integer call() throws Exception {
                service.connect(bootstrap, security);
                return service.listTopics().size();
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Connected — " + task.getValue() + " topic(s)");
            logger.accept("Kafka connected — " + task.getValue() + " topics");
            explorer.setExplorer(new KafkaExplorer(service));
            explorer.load();
            connectBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Connect failed: " + task.getException().getMessage());
            logger.accept("Kafka connect FAILED: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task, "kafka-connect");
    }

    private void produce() {
        String topic = Env.resolve(produceTopic.getText().trim());   // resolve ${VAR} in topic/key/value
        if (topic.isEmpty()) { produceStatus.setText("Enter a topic"); return; }
        produceStatus.getStyleClass().setAll("meta-label");
        produceStatus.setText("Sending…");
        Task<KafkaService.SendResult> task = new Task<>() {
            @Override protected KafkaService.SendResult call() throws Exception {
                return service.send(topic, Env.resolve(produceKey.getText()), Env.resolve(produceValue.getText()));
            }
        };
        task.setOnSucceeded(e -> {
            KafkaService.SendResult r = task.getValue();
            produceStatus.getStyleClass().setAll("status-2xx");
            produceStatus.setText("✓ partition " + r.partition() + " · offset " + r.offset());
            logger.accept("Kafka produced → " + topic + " p" + r.partition() + " @" + r.offset());
        });
        task.setOnFailed(e -> {
            produceStatus.getStyleClass().setAll("status-err");
            produceStatus.setText("✖ " + task.getException().getMessage());
        });
        runBg(task, "kafka-produce");
    }

    /**
     * Peeks up to 100 messages from the topic via {@link KafkaService#browse} — a read with no
     * consumer-group side effects (no join, no commit) — replacing the table contents. Runs off the FX thread.
     */
    private void browseTopic() {
        String topic = Env.resolve(consumeTopic.getText().trim());
        if (topic.isEmpty()) { append("Enter a topic to browse"); return; }
        boolean fromStart = fromBeginning.isSelected();
        append("👁 browsing " + topic + " (no commit)…");
        Task<List<KafkaService.KafkaMessage>> task = new Task<>() {
            @Override protected List<KafkaService.KafkaMessage> call() { return service.browse(topic, 100, fromStart); }
        };
        task.setOnSucceeded(e -> {
            consumedMessages.setAll(task.getValue());
            append("👁 browsed " + task.getValue().size() + " message(s) from " + topic);
        });
        task.setOnFailed(e -> append("⚠ browse: " + task.getException().getMessage()));
        runBg(task, "kafka-browse");
    }

    private void toggleConsume() {
        if (consumeToggle.isSelected()) {
            String topic = Env.resolve(consumeTopic.getText().trim());   // resolve ${VAR} in topic/group
            if (topic.isEmpty()) { consumeToggle.setSelected(false); return; }
            consumeToggle.setText("Stop");
            append("⇆ subscribing to " + topic);
            service.startConsuming(topic, Env.resolve(consumeGroup.getText().trim()), fromBeginning.isSelected(),
                    new KafkaService.MessageListener() {
                        @Override public void onMessage(KafkaService.KafkaMessage m) {
                            Platform.runLater(() -> {
                                consumedMessages.add(m);
                                if (consumedMessages.size() > MAX_MESSAGES) consumedMessages.remove(0);
                            });
                        }
                        @Override public void onError(Throwable t) {
                            Platform.runLater(() -> append("⚠ " + t.getMessage()));
                        }
                    });
        } else {
            consumeToggle.setText("Start consuming");
            service.stopConsuming();
            append("⇆ stopped");
        }
    }

    private void append(String line) {
        consumeLog.appendText(LocalTime.now().format(TIME) + "  " + line + "\n");
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
