package com.nexuslink.ui.main;

import com.nexuslink.core.history.HistoryEntry;
import com.nexuslink.core.history.HistoryStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Browsable, searchable request-history panel backed by {@link HistoryStore}.
 * Double-click (or the Replay button) re-opens a request via the supplied callback.
 */
public final class HistoryPanel extends BorderPane {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HistoryStore store;
    private final Consumer<HistoryEntry> onReplay;
    private final ListView<HistoryEntry> list = new ListView<>();
    private final TextField search = new TextField();

    public HistoryPanel(HistoryStore store, Consumer<HistoryEntry> onReplay) {
        this.store = store;
        this.onReplay = onReplay;

        search.setPromptText("🔍  Search history…");
        search.getStyleClass().add("global-search");
        search.textProperty().addListener((o, ov, q) -> refresh(q));

        Button replay = new Button("Replay");
        replay.getStyleClass().add("btn-secondary");
        replay.setOnAction(e -> replaySelected());

        Button star = new Button("★");
        star.getStyleClass().add("btn-secondary");
        star.setOnAction(e -> toggleFavoriteSelected());

        HBox toolbar = new HBox(6, search, replay, star);
        HBox.setHgrow(search, Priority.ALWAYS);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        list.setCellFactory(lv -> new HistoryCell());
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 2) replaySelected(); });
        VBox.setVgrow(list, Priority.ALWAYS);

        setTop(toolbar);
        setCenter(list);
        getStyleClass().add("history-panel");
        refresh("");
    }

    /** Called after a new request completes — prepend it and keep the view fresh. */
    public void onNewEntry() {
        if (search.getText().isBlank()) refresh("");
    }

    private void refresh(String query) {
        List<HistoryEntry> entries = (query == null || query.isBlank())
                ? store.recent(200) : store.search(query, 200);
        Platform.runLater(() -> {
            list.getItems().setAll(entries);
        });
    }

    private void replaySelected() {
        HistoryEntry sel = list.getSelectionModel().getSelectedItem();
        if (sel != null) onReplay.accept(sel);
    }

    private void toggleFavoriteSelected() {
        HistoryEntry sel = list.getSelectionModel().getSelectedItem();
        if (sel != null) {
            store.setFavorite(sel.id(), !sel.favorite());
            refresh(search.getText());
        }
    }

    /** Renders one history row: status chip · summary · time. */
    private static final class HistoryCell extends ListCell<HistoryEntry> {
        @Override
        protected void updateItem(HistoryEntry e, boolean empty) {
            super.updateItem(e, empty);
            if (empty || e == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String fav = e.favorite() ? "★ " : "";
            String status = e.statusCode() > 0 ? String.valueOf(e.statusCode()) : "ERR";
            Label statusLbl = new Label(status);
            statusLbl.getStyleClass().add(e.statusCode() == 0
                    ? "status-err" : "status-" + (e.statusCode() / 100) + "xx");
            statusLbl.setMinWidth(38);

            Label summary = new Label(fav + e.summary());
            summary.getStyleClass().add("history-summary");
            HBox.setHgrow(summary, Priority.ALWAYS);
            summary.setMaxWidth(Double.MAX_VALUE);

            Label time = new Label(TIME.format(Instant.ofEpochMilli(e.timestamp()))
                    + "  " + e.durationMs() + "ms");
            time.getStyleClass().add("meta-label");

            HBox row = new HBox(8, statusLbl, summary, time);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }
    }
}
