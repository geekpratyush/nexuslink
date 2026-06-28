package com.nexuslink.ui.kafka;

import com.nexuslink.plugin.ResourceNode;
import com.nexuslink.protocol.kafka.KafkaExplorer;
import com.nexuslink.protocol.kafka.KafkaMessageExporter;
import com.nexuslink.protocol.kafka.KafkaService;
import com.nexuslink.protocol.kafka.PayloadFormatter;
import com.nexuslink.ui.env.Env;
import com.nexuslink.ui.explorer.ResourceExplorerView;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

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
    private final TableView<KafkaService.KafkaMessage> messageTable = new TableView<>(consumedMessages);
    private final ComboBox<PayloadFormatter.Format> formatCombo = new ComboBox<>();

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

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("kafka-client"));

        HBox row1 = new HBox(8, label("Brokers:"), bootstrapField, connectBtn, helpBtn);
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
        tabs.getTabs().addAll(new Tab("Produce", buildProduce()), new Tab("Consume", buildConsume()));

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
        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-secondary");
        clear.setOnAction(e -> { consumedMessages.clear(); consumeLog.clear(); });

        formatCombo.getItems().addAll(PayloadFormatter.Format.values());
        formatCombo.setValue(PayloadFormatter.Format.STRING);
        // Re-render key/value cells through the newly selected formatter (raw data is untouched).
        formatCombo.valueProperty().addListener((o, a, b) -> messageTable.refresh());

        HBox top = new HBox(8, label("Topic:"), consumeTopic, label("Group:"), consumeGroup,
                fromBeginning, consumeToggle, label("Format:"), formatCombo, exportJson, exportCsv, clear);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, top, messageTable, consumeLog);
        box.setPadding(new Insets(8));
        VBox.setVgrow(messageTable, Priority.ALWAYS);
        return box;
    }

    private void buildMessageTable() {
        messageTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        messageTable.setPlaceholder(new Label("Consumed records appear here. Start consuming to populate."));

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
