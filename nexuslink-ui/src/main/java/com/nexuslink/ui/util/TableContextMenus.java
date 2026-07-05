package com.nexuslink.ui.util;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.ArrayList;
import java.util.List;

/**
 * One-call, desktop-style right-click interactivity for any {@link TableView}: <em>Copy</em> (the
 * focused cell), <em>Copy row(s)</em> (tab-separated, spreadsheet-friendly), and <em>Copy all as
 * CSV</em>. Also binds {@code Ctrl/Cmd+C} to copy the selection. The clipboard text is built from the
 * table's visible columns via {@link TableColumn#getCellData(int)}, so it works for any row type
 * without per-view code. Formatting is delegated to the tested {@link TableCopy}.
 *
 * <p>Menu items that need a target are disabled when there is no selection (Copy all is always on
 * while the table has rows). If a table already has a {@link TableView#getContextMenu() context
 * menu}, the copy items are appended to it rather than replacing it.</p>
 */
public final class TableContextMenus {

    private TableContextMenus() {}

    /** Installs the copy context menu + Ctrl/Cmd+C on {@code table}. */
    public static <T> void installCopy(TableView<T> table) {
        MenuItem copyCell = new MenuItem("Copy");
        copyCell.setOnAction(e -> copyFocusedCell(table));
        MenuItem copyRows = new MenuItem("Copy row");
        copyRows.setOnAction(e -> copySelectedRows(table));
        MenuItem copyAll = new MenuItem("Copy all as CSV");
        copyAll.setOnAction(e -> copyAllAsCsv(table));

        ContextMenu menu = table.getContextMenu();
        if (menu == null) {
            menu = new ContextMenu();
            table.setContextMenu(menu);
        } else if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        menu.getItems().addAll(copyCell, copyRows, new SeparatorMenuItem(), copyAll);

        // Reflect selection state each time the menu opens; pluralise the row label.
        menu.setOnShowing(e -> {
            boolean hasSelection = !table.getSelectionModel().getSelectedCells().isEmpty()
                    || !table.getSelectionModel().getSelectedItems().isEmpty();
            copyCell.setDisable(!hasSelection);
            copyRows.setDisable(!hasSelection);
            int n = Math.max(table.getSelectionModel().getSelectedItems().size(), 0);
            copyRows.setText(n > 1 ? "Copy " + n + " rows" : "Copy row");
            copyAll.setDisable(table.getItems().isEmpty());
        });

        KeyCombination copyKey = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        table.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, ev -> {
            if (copyKey.match(ev)) { copySelectedRows(table); ev.consume(); }
        });
    }

    // ---- actions ----

    private static <T> void copyFocusedCell(TableView<T> table) {
        @SuppressWarnings("unchecked")
        List<TablePosition> cells = table.getSelectionModel().getSelectedCells();
        if (!cells.isEmpty()) {
            TablePosition<?, ?> pos = cells.get(0);
            TableColumn<T, ?> col = table.getColumns().isEmpty() ? null
                    : columnAt(table, pos.getColumn());
            if (col != null && pos.getRow() >= 0) {
                put(str(col.getCellData(pos.getRow())));
                return;
            }
        }
        copySelectedRows(table);   // fall back to the whole row when no cell is resolvable
    }

    private static <T> void copySelectedRows(TableView<T> table) {
        List<Integer> rowIndexes = selectedRowIndexes(table);
        if (rowIndexes.isEmpty()) return;
        List<TableColumn<T, ?>> cols = visibleColumns(table);
        List<List<String>> rows = new ArrayList<>();
        for (int r : rowIndexes) rows.add(rowValues(cols, r));
        put(TableCopy.toTsv(rows));
    }

    private static <T> void copyAllAsCsv(TableView<T> table) {
        List<TableColumn<T, ?>> cols = visibleColumns(table);
        List<List<String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>();
        for (TableColumn<T, ?> c : cols) header.add(c.getText());
        rows.add(header);
        for (int r = 0; r < table.getItems().size(); r++) rows.add(rowValues(cols, r));
        put(TableCopy.toCsv(rows));
    }

    // ---- helpers ----

    private static <T> List<Integer> selectedRowIndexes(TableView<T> table) {
        List<Integer> out = new ArrayList<>();
        for (int i : table.getSelectionModel().getSelectedIndices()) if (i >= 0) out.add(i);
        if (out.isEmpty() && table.getSelectionModel().getSelectedItem() != null) {
            int i = table.getItems().indexOf(table.getSelectionModel().getSelectedItem());
            if (i >= 0) out.add(i);
        }
        return out;
    }

    private static <T> List<TableColumn<T, ?>> visibleColumns(TableView<T> table) {
        List<TableColumn<T, ?>> cols = new ArrayList<>();
        for (TableColumn<T, ?> c : table.getColumns()) if (c.isVisible()) cols.add(c);
        return cols;
    }

    private static <T> TableColumn<T, ?> columnAt(TableView<T> table, int index) {
        return index >= 0 && index < table.getColumns().size() ? table.getColumns().get(index) : null;
    }

    private static <T> List<String> rowValues(List<TableColumn<T, ?>> cols, int rowIndex) {
        List<String> values = new ArrayList<>();
        for (TableColumn<T, ?> c : cols) values.add(str(c.getCellData(rowIndex)));
        return values;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void put(String text) {
        if (text == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
