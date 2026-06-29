package com.nexuslink.ui.snmp;

import com.nexuslink.protocol.snmp.OidRegistry;
import com.nexuslink.protocol.snmp.SnmpService;
import com.nexuslink.protocol.snmp.SnmpTrapReceiver;
import com.nexuslink.ui.env.Env;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * SNMP view with two tabs. <b>Browser</b> opens a community-based v1/v2c session to an agent, then
 * GETs a specific OID or WALKs a subtree (GETNEXT), showing results in an OID / type / value table.
 * <b>Traps</b> listens on a UDP port (default 162) for incoming v1/v2c traps and notifications and
 * appends each, live, to a table (time, source, trap OID, varbinds) with OIDs resolved to MIB names.
 * Built on SNMP4J; {@code ${VAR}} is resolved in the host/community/OID fields. (SNMPv3 / USM is on
 * the roadmap.)
 */
public final class SnmpView extends BorderPane {

    /** Row model for the results table (JavaFX-bean style for PropertyValueFactory). */
    public static final class Row {
        private final SimpleStringProperty name;
        private final SimpleStringProperty oid;
        private final SimpleStringProperty type;
        private final SimpleStringProperty value;
        Row(SnmpService.VarBind vb) {
            this.name = new SimpleStringProperty(OidRegistry.nameFor(vb.oid()));   // symbolic MIB name (or numeric)
            this.oid = new SimpleStringProperty(vb.oid());
            this.type = new SimpleStringProperty(vb.type());
            this.value = new SimpleStringProperty(vb.value());
        }
        public String getName() { return name.get(); }
        public String getOid() { return oid.get(); }
        public String getType() { return type.get(); }
        public String getValue() { return value.get(); }
    }

    /** Row model for the traps table (one received trap per row). */
    public static final class TrapRow {
        private final SimpleStringProperty time;
        private final SimpleStringProperty source;
        private final SimpleStringProperty version;
        private final SimpleStringProperty trapOid;
        private final SimpleStringProperty varbinds;
        TrapRow(SnmpTrapReceiver.Trap t) {
            this.time = new SimpleStringProperty(TIME_FMT.format(t.timestamp()));
            this.source = new SimpleStringProperty(t.source());
            this.version = new SimpleStringProperty(t.version());
            String oid = t.trapOid() == null || t.trapOid().isBlank()
                    ? "(none)" : OidRegistry.nameFor(t.trapOid());
            this.trapOid = new SimpleStringProperty(oid);
            this.varbinds = new SimpleStringProperty(summarize(t));
        }
        public String getTime() { return time.get(); }
        public String getSource() { return source.get(); }
        public String getVersion() { return version.get(); }
        public String getTrapOid() { return trapOid.get(); }
        public String getVarbinds() { return varbinds.get(); }

        /** Renders the variable bindings as {@code name=value} pairs with OIDs resolved to MIB names. */
        private static String summarize(SnmpTrapReceiver.Trap t) {
            return t.varBinds().stream()
                    .map(vb -> OidRegistry.nameFor(vb.oid()) + "=" + vb.value())
                    .collect(Collectors.joining(", "));
        }
    }

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SnmpService service = new SnmpService();

    private final TextField hostField = new TextField();
    private final TextField portField = new TextField("161");
    private final TextField communityField = new TextField("public");
    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final Button connectBtn = new Button("Open");
    private final Label statusLabel = new Label("Not open");

    private final TextField oidField = new TextField("1.3.6.1.2.1.1");
    private final Button getBtn = new Button("GET");
    private final Button walkBtn = new Button("WALK");

    private final TableView<Row> table = new TableView<>();

    // ---- Traps tab ----
    private final SnmpTrapReceiver trapReceiver = new SnmpTrapReceiver(this::onTrap);
    private final TextField trapPortField = new TextField(String.valueOf(SnmpTrapReceiver.DEFAULT_PORT));
    private final TextField trapFilterField = new TextField();
    private final Button trapToggleBtn = new Button("Start");
    private final Label trapStatusLabel = new Label("Stopped");
    private final TableView<TrapRow> trapTable = new TableView<>();

    private Consumer<String> logger = s -> {};

    public SnmpView() {
        getStyleClass().add("snmp-view");
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Browser", buildBrowserPane()), new Tab("Traps", buildTrapsPane()));
        setCenter(tabs);
    }

    private BorderPane buildBrowserPane() {
        BorderPane pane = new BorderPane();
        pane.setTop(buildBar());
        pane.setCenter(buildBody());
        return pane;
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    /** Pre-fills the agent host (and community when supplied) when opening a saved connection. */
    public void prefill(String host, String community) {
        if (host != null && !host.isBlank()) hostField.setText(host);
        if (community != null && !community.isBlank()) communityField.setText(community);
    }

    private VBox buildBar() {
        hostField.getStyleClass().add("nl-field");
        hostField.setPromptText("agent host, e.g. 127.0.0.1");
        HBox.setHgrow(hostField, Priority.ALWAYS);
        portField.getStyleClass().add("nl-field");
        portField.setPrefWidth(70);
        communityField.getStyleClass().add("nl-field");
        communityField.setPrefWidth(130);
        versionCombo.getItems().addAll("2c", "1");
        versionCombo.setValue("2c");

        connectBtn.getStyleClass().add("btn-primary");
        connectBtn.setOnAction(e -> toggleOpen());

        Button helpBtn = new Button("?");
        helpBtn.getStyleClass().add("btn-secondary");
        helpBtn.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("snmp"));

        HBox row = new HBox(8, label("Host:"), hostField, label("Port:"), portField,
                label("Community:"), communityField, label("v:"), versionCombo, connectBtn, helpBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 4, 10));

        statusLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(statusLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private VBox buildBody() {
        oidField.getStyleClass().add("nl-field");
        oidField.setPromptText("OID or MIB name, e.g. 1.3.6.1.2.1.1.1.0 or sysDescr.0");
        HBox.setHgrow(oidField, Priority.ALWAYS);
        getBtn.getStyleClass().add("btn-secondary");
        getBtn.setOnAction(e -> doGet());
        walkBtn.getStyleClass().add("btn-secondary");
        walkBtn.setOnAction(e -> doWalk());
        HBox opRow = new HBox(8, label("OID:"), oidField, getBtn, walkBtn);
        opRow.setAlignment(Pos.CENTER_LEFT);

        TableColumn<Row, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(150);
        TableColumn<Row, String> oidCol = new TableColumn<>("OID");
        oidCol.setCellValueFactory(new PropertyValueFactory<>("oid"));
        oidCol.setPrefWidth(250);
        TableColumn<Row, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(110);
        TableColumn<Row, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(new PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(330);
        table.getColumns().addAll(List.of(nameCol, oidCol, typeCol, valueCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Open a session, then GET an OID or WALK a subtree."));
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox box = new VBox(8, opRow, new Separator(), table);
        box.setPadding(new Insets(10));
        return box;
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        return l;
    }

    private void toggleOpen() {
        if (service.isOpen()) {
            service.close();
            statusLabel.getStyleClass().setAll("meta-label");
            statusLabel.setText("Closed");
            connectBtn.setText("Open");
            return;
        }
        String host = Env.resolve(hostField.getText().trim());   // resolve ${VAR} against active environment
        if (host.isEmpty()) { statusLabel.setText("Enter an agent host"); return; }
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException ex) { statusLabel.setText("Port must be a number"); return; }
        String community = Env.resolve(communityField.getText().trim());
        String version = versionCombo.getValue();

        connectBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText("Opening…");
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                service.open(host, port, community, version);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText("Open — " + host + ":" + port + " (v" + version + ")");
            connectBtn.setText("Close");
            connectBtn.setDisable(false);
            logger.accept("SNMP session open → " + host + ":" + port);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Open failed: " + task.getException().getMessage());
            connectBtn.setDisable(false);
        });
        runBg(task, "snmp-open");
    }

    private void doGet() {
        String oid = resolveOid(oidField.getText().trim());   // resolve ${VAR} + symbolic MIB name
        if (!checkReady(oid)) return;
        runQuery("GET " + oid, () -> service.get(oid));
    }

    private void doWalk() {
        String oid = resolveOid(oidField.getText().trim());
        if (!checkReady(oid)) return;
        runQuery("WALK " + oid, () -> service.walk(oid, 0));
    }

    /** Resolves {@code ${VAR}} then a symbolic MIB name (e.g. {@code sysDescr.0}) to a numeric OID. */
    private String resolveOid(String raw) {
        String resolved = Env.resolve(raw);
        if (!SnmpService.isValidOid(resolved)) {
            return OidRegistry.oidFor(resolved).orElse(resolved);   // fall back to the original on miss
        }
        return resolved;
    }

    private boolean checkReady(String oid) {
        if (!service.isOpen()) { statusLabel.setText("Open a session first"); return false; }
        if (!SnmpService.isValidOid(oid)) {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText("Not a valid numeric OID: " + oid);
            return false;
        }
        return true;
    }

    private interface Query { List<SnmpService.VarBind> run() throws Exception; }

    private void runQuery(String label, Query query) {
        getBtn.setDisable(true);
        walkBtn.setDisable(true);
        statusLabel.getStyleClass().setAll("meta-label");
        statusLabel.setText(label + "…");
        logger.accept("SNMP " + label);

        Task<List<SnmpService.VarBind>> task = new Task<>() {
            @Override protected List<SnmpService.VarBind> call() throws Exception { return query.run(); }
        };
        task.setOnSucceeded(e -> {
            List<SnmpService.VarBind> binds = task.getValue();
            table.getItems().setAll(binds.stream().map(Row::new).toList());
            statusLabel.getStyleClass().setAll("status-2xx");
            statusLabel.setText(label + " — " + binds.size() + " result" + (binds.size() == 1 ? "" : "s"));
            logger.accept("SNMP " + label + " → " + binds.size() + " results");
            getBtn.setDisable(false);
            walkBtn.setDisable(false);
        });
        task.setOnFailed(e -> {
            statusLabel.getStyleClass().setAll("status-err");
            statusLabel.setText(label + " failed: " + task.getException().getMessage());
            logger.accept("SNMP " + label + " FAILED: " + task.getException().getMessage());
            getBtn.setDisable(false);
            walkBtn.setDisable(false);
        });
        runBg(task, "snmp-query");
    }

    // ---- Traps tab ----

    private VBox buildTrapsPane() {
        trapPortField.getStyleClass().add("nl-field");
        trapPortField.setPrefWidth(70);
        trapFilterField.getStyleClass().add("nl-field");
        trapFilterField.setPrefWidth(130);
        trapFilterField.setPromptText("community (all)");

        trapToggleBtn.getStyleClass().add("btn-primary");
        trapToggleBtn.setOnAction(e -> toggleTraps());

        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> trapTable.getItems().clear());

        trapStatusLabel.getStyleClass().add("meta-label");

        HBox ctlRow = new HBox(8, label("Port:"), trapPortField, label("Community filter:"),
                trapFilterField, trapToggleBtn, clearBtn);
        ctlRow.setAlignment(Pos.CENTER_LEFT);

        TableColumn<TrapRow, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
        timeCol.setPrefWidth(90);
        TableColumn<TrapRow, String> srcCol = new TableColumn<>("Source");
        srcCol.setCellValueFactory(new PropertyValueFactory<>("source"));
        srcCol.setPrefWidth(170);
        TableColumn<TrapRow, String> verCol = new TableColumn<>("Ver");
        verCol.setCellValueFactory(new PropertyValueFactory<>("version"));
        verCol.setPrefWidth(50);
        TableColumn<TrapRow, String> oidCol = new TableColumn<>("Trap OID");
        oidCol.setCellValueFactory(new PropertyValueFactory<>("trapOid"));
        oidCol.setPrefWidth(180);
        TableColumn<TrapRow, String> vbCol = new TableColumn<>("Varbinds");
        vbCol.setCellValueFactory(new PropertyValueFactory<>("varbinds"));
        vbCol.setPrefWidth(360);
        trapTable.getColumns().addAll(List.of(timeCol, srcCol, verCol, oidCol, vbCol));
        trapTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        trapTable.setPlaceholder(new Label("Start the listener to receive SNMP v1/v2c traps."));
        VBox.setVgrow(trapTable, Priority.ALWAYS);

        VBox box = new VBox(8, ctlRow, new HBox(trapStatusLabel), new Separator(), trapTable);
        box.setPadding(new Insets(10));
        return box;
    }

    private void toggleTraps() {
        if (trapReceiver.isListening()) {
            trapReceiver.stop();
            trapStatusLabel.getStyleClass().setAll("meta-label");
            trapStatusLabel.setText("Stopped");
            trapToggleBtn.setText("Start");
            logger.accept("SNMP trap listener stopped");
            return;
        }
        int port;
        try { port = Integer.parseInt(trapPortField.getText().trim()); }
        catch (NumberFormatException ex) {
            trapStatusLabel.getStyleClass().setAll("status-err");
            trapStatusLabel.setText("Port must be a number");
            return;
        }
        try {
            trapReceiver.start(port);
            trapStatusLabel.getStyleClass().setAll("status-2xx");
            trapStatusLabel.setText("Listening on udp/" + trapReceiver.boundPort());
            trapToggleBtn.setText("Stop");
            logger.accept("SNMP trap listener started on udp/" + trapReceiver.boundPort());
        } catch (IOException ex) {
            trapStatusLabel.getStyleClass().setAll("status-err");
            trapStatusLabel.setText("Start failed: " + ex.getMessage());
            logger.accept("SNMP trap listener FAILED: " + ex.getMessage());
        }
    }

    /** Receiver callback (SNMP4J thread) — applies the community filter, then appends on the FX thread. */
    private void onTrap(SnmpTrapReceiver.Trap trap) {
        String filter = trapFilterField.getText().trim();
        if (!filter.isEmpty() && !filter.equals(trap.community())) return;
        Platform.runLater(() -> {
            trapTable.getItems().add(0, new TrapRow(trap));   // newest first
            logger.accept("SNMP trap from " + trap.source() + " — " + OidRegistry.nameFor(trap.trapOid()));
        });
    }

    private void runBg(Task<?> task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }
}
