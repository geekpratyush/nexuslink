package com.nexuslink.ui.connections;

import com.nexuslink.core.event.ConnectionEvent;
import com.nexuslink.core.event.ConnectionRegistry;
import com.nexuslink.core.event.EventBus;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Live connection-state dashboard (§9.1): three headline tiles (active / idle / failed) plus a
 * per-protocol breakdown table, refreshed off {@link ConnectionEvent}s posted on the
 * {@link EventBus} by protocol services via {@link ConnectionRegistry}.
 *
 * <p>Read-only. The bus holds listeners weakly, so we keep a strong reference to the subscribed
 * lambda for the lifetime of the panel.
 */
public final class ConnectionStatePanel extends BorderPane {

    /** A per-protocol row bound to that protocol's current counts. */
    public static final class Row {
        final SimpleStringProperty protocol, active, idle, failed, total;
        Row(String proto, ConnectionRegistry.Counts c) {
            protocol = new SimpleStringProperty(proto);
            active = new SimpleStringProperty(Integer.toString(c.active()));
            idle = new SimpleStringProperty(Integer.toString(c.idle()));
            failed = new SimpleStringProperty(Integer.toString(c.failed()));
            total = new SimpleStringProperty(Integer.toString(c.total()));
        }
        public String getProtocol() { return protocol.get(); }
        public String getActive() { return active.get(); }
        public String getIdle() { return idle.get(); }
        public String getFailed() { return failed.get(); }
        public String getTotal() { return total.get(); }
    }

    private final ConnectionRegistry registry;
    private final Label activeValue = tileValue();
    private final Label idleValue = tileValue();
    private final Label failedValue = tileValue();
    private final Label updatedLabel = new Label("no activity yet");
    private final TableView<Row> table = new TableView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();

    // strong refs — the EventBus keeps listeners weakly
    private final Consumer<ConnectionEvent> listener = this::onEvent;
    @SuppressWarnings("unused")
    private final EventBus.Subscription subscription;

    public ConnectionStatePanel() {
        this(ConnectionRegistry.global(), EventBus.get());
    }

    public ConnectionStatePanel(ConnectionRegistry registry, EventBus bus) {
        this.registry = registry;
        getStyleClass().add("conn-state-panel");
        setPadding(new Insets(16));

        HBox tiles = new HBox(12,
                tile("Active", activeValue, "conn-tile-active"),
                tile("Idle", idleValue, "conn-tile-idle"),
                tile("Failed", failedValue, "conn-tile-failed"));
        tiles.setAlignment(Pos.CENTER_LEFT);

        Label heading = new Label("By protocol");
        heading.getStyleClass().add("conn-section-header");
        VBox.setMargin(heading, new Insets(16, 0, 6, 0));

        buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        updatedLabel.getStyleClass().add("stat-chip");

        VBox content = new VBox(tiles, heading, table, updatedLabel);
        content.setFillWidth(true);
        setCenter(content);

        this.subscription = bus.subscribe(ConnectionEvent.class, listener);
        refresh(); // seed from whatever is already tracked
    }

    private void buildTable() {
        TableColumn<Row, String> proto = new TableColumn<>("Protocol");
        proto.setCellValueFactory(c -> c.getValue().protocol);
        proto.setPrefWidth(160);
        TableColumn<Row, String> active = new TableColumn<>("Active");
        active.setCellValueFactory(c -> c.getValue().active);
        TableColumn<Row, String> idle = new TableColumn<>("Idle");
        idle.setCellValueFactory(c -> c.getValue().idle);
        TableColumn<Row, String> failed = new TableColumn<>("Failed");
        failed.setCellValueFactory(c -> c.getValue().failed);
        TableColumn<Row, String> total = new TableColumn<>("Total");
        total.setCellValueFactory(c -> c.getValue().total);
        table.getColumns().addAll(java.util.List.of(proto, active, idle, failed, total));
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No connections yet — open a protocol and connect."));
        com.nexuslink.ui.util.TableContextMenus.installCopy(table);
    }

    private void onEvent(ConnectionEvent e) {
        Platform.runLater(this::refresh);
    }

    /** Re-reads the registry and repaints tiles + table. Must run on the FX thread. */
    public void refresh() {
        var totals = registry.counts();
        activeValue.setText(Integer.toString(totals.active()));
        idleValue.setText(Integer.toString(totals.idle()));
        failedValue.setText(Integer.toString(totals.failed()));

        rows.clear();
        for (Map.Entry<String, ConnectionRegistry.Counts> en : registry.byProtocol().entrySet()) {
            rows.add(new Row(en.getKey(), en.getValue()));
        }
        updatedLabel.setText(totals.total() == 0
                ? "no active connections"
                : totals.total() + " tracked · updated " + shortTime());
    }

    private static String shortTime() {
        return java.time.LocalTime.now().withNano(0).toString();
    }

    private static VBox tile(String caption, Label value, String styleClass) {
        Label cap = new Label(caption.toUpperCase());
        cap.getStyleClass().add("conn-tile-caption");
        VBox box = new VBox(4, value, cap);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(12, 18, 12, 18));
        box.getStyleClass().addAll("conn-tile", styleClass);
        box.setMinWidth(120);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.NEVER);
        return box;
    }

    private static Label tileValue() {
        Label l = new Label("0");
        l.getStyleClass().add("conn-tile-value");
        return l;
    }
}
