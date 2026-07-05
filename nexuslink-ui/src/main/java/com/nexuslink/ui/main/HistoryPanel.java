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
        list.setContextMenu(buildContextMenu());
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

    /** Right-click actions on a history row. */
    private ContextMenu buildContextMenu() {
        MenuItem replay = new MenuItem("Replay");
        replay.setOnAction(e -> replaySelected());
        MenuItem copy = new MenuItem("Copy summary");
        copy.setOnAction(e -> copySelected());
        MenuItem favorite = new MenuItem("Toggle favorite");
        favorite.setOnAction(e -> toggleFavoriteSelected());
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> deleteSelected());
        MenuItem clearAll = new MenuItem("Clear all history…");
        clearAll.setOnAction(e -> clearAll());

        ContextMenu menu = new ContextMenu(replay, copy, favorite,
                new SeparatorMenuItem(), delete, clearAll);
        menu.setOnShowing(e -> {
            boolean hasSel = list.getSelectionModel().getSelectedItem() != null;
            replay.setDisable(!hasSel);
            copy.setDisable(!hasSel);
            favorite.setDisable(!hasSel);
            delete.setDisable(!hasSel);
            clearAll.setDisable(list.getItems().isEmpty());
        });
        return menu;
    }

    private void replaySelected() {
        HistoryEntry sel = list.getSelectionModel().getSelectedItem();
        if (sel != null) onReplay.accept(sel);
    }

    private void copySelected() {
        HistoryEntry sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(sel.summary());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private void deleteSelected() {
        HistoryEntry sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        store.delete(sel.id());
        refresh(search.getText());
    }

    private void clearAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete all " + list.getItems().size() + " history entries? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.setTitle("Clear history");
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            store.clear();
            refresh("");
        });
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

            // Pull a leading HTTP verb (e.g. "GET https://…") into a coloured badge, if present.
            String text = e.summary();
            String[] parts = text.split("\\s+", 2);
            Label methodBadge = null;
            if (parts.length == 2 && com.nexuslink.ui.util.HttpMethods.isMethod(parts[0])) {
                methodBadge = com.nexuslink.ui.util.HttpMethods.badge(parts[0]);
                text = parts[1];
            }

            Label summary = new Label(fav + text);
            summary.getStyleClass().add("history-summary");
            HBox.setHgrow(summary, Priority.ALWAYS);
            summary.setMaxWidth(Double.MAX_VALUE);

            Label time = new Label(TIME.format(Instant.ofEpochMilli(e.timestamp()))
                    + "  " + e.durationMs() + "ms");
            time.getStyleClass().add("meta-label");

            HBox row = methodBadge == null
                    ? new HBox(8, statusLbl, summary, time)
                    : new HBox(8, statusLbl, methodBadge, summary, time);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }
    }
}
