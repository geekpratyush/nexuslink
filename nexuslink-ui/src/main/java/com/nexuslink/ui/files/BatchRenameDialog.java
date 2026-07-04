package com.nexuslink.ui.files;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * The batch-rename dialog: the user composes a find/replace (literal or regex), prefix/suffix,
 * optional sequential numbering and a case transform, and sees a live before → after preview with
 * colliding targets flagged. All the actual name computation is delegated to the tested pure
 * {@link BulkRename} — this class is only the UI over it, mirroring how {@code LdapFilterDialog} wraps
 * {@code LdapFilterBuilder}. Returns the previewed results when accepted, or empty if cancelled; the
 * caller applies the rows whose name actually changed.
 */
final class BatchRenameDialog {

    private final List<String> names;

    private final TextField find = new TextField();
    private final TextField replace = new TextField();
    private final CheckBox regex = new CheckBox("Regex");
    private final TextField prefix = new TextField();
    private final TextField suffix = new TextField();
    private final CheckBox addNumber = new CheckBox("Add number");
    private final Spinner<Integer> start = new Spinner<>(0, 1_000_000, 1);
    private final Spinner<Integer> step = new Spinner<>(1, 1000, 1);
    private final Spinner<Integer> pad = new Spinner<>(0, 9, 0);
    private final ComboBox<BulkRename.Case> nameCase = new ComboBox<>();
    private final TableView<BulkRename.Result> preview = new TableView<>();
    private final Label warning = new Label();

    private BatchRenameDialog(List<String> names) {
        this.names = names;
    }

    /** Opens the dialog for {@code names}; returns the preview rows when accepted. */
    static Optional<List<BulkRename.Result>> open(Window owner, List<String> names) {
        return new BatchRenameDialog(names).show(owner);
    }

    private Optional<List<BulkRename.Result>> show(Window owner) {
        Dialog<List<BulkRename.Result>> dialog = new Dialog<>();
        dialog.setTitle("Batch rename");
        dialog.setHeaderText("Rename " + names.size() + " item(s)");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().sceneProperty().addListener((o, ov, sc) -> {
            if (sc != null) {
                com.nexuslink.ui.theme.ThemeManager.get().register(sc);
                recompute();   // now the OK button exists, so its disabled state can be set
            }
        });

        for (TextField tf : new TextField[]{find, replace, prefix, suffix}) {
            tf.getStyleClass().add("nl-field");
            tf.textProperty().addListener((o, ov, nv) -> recompute());
        }
        regex.setOnAction(e -> recompute());
        addNumber.setOnAction(e -> recompute());
        start.setEditable(true);
        step.setEditable(true);
        pad.setEditable(true);
        for (Spinner<Integer> sp : List.of(start, step, pad)) {
            sp.setPrefWidth(70);
            sp.valueProperty().addListener((o, ov, nv) -> recompute());
        }
        nameCase.getItems().setAll(BulkRename.Case.values());
        nameCase.setValue(BulkRename.Case.KEEP);
        nameCase.setOnAction(e -> recompute());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Find:"), find, new Label("Replace:"), replace, regex);
        form.addRow(1, new Label("Prefix:"), prefix, new Label("Suffix:"), suffix);
        HBox numberBox = new HBox(8, addNumber, new Label("start"), start,
                new Label("step"), step, new Label("pad"), pad);
        numberBox.setAlignment(Pos.CENTER_LEFT);
        form.add(new Label("Number:"), 0, 2);
        form.add(numberBox, 1, 2, 4, 1);
        form.add(new Label("Case:"), 0, 3);
        form.add(nameCase, 1, 3);
        GridPane.setHgrow(find, Priority.ALWAYS);
        GridPane.setHgrow(replace, Priority.ALWAYS);
        GridPane.setHgrow(prefix, Priority.ALWAYS);
        GridPane.setHgrow(suffix, Priority.ALWAYS);

        buildPreviewTable();
        warning.getStyleClass().add("meta-label");

        VBox content = new VBox(10, form, new Separator(), new Label("Preview:"), preview, warning);
        content.setPadding(new Insets(12));
        content.setPrefWidth(620);
        dialog.getDialogPane().setContent(content);

        recompute();
        dialog.setResultConverter(bt -> bt == ButtonType.OK ? List.copyOf(preview.getItems()) : null);
        return dialog.showAndWait();
    }

    @SuppressWarnings("unchecked")
    private void buildPreviewTable() {
        TableColumn<BulkRename.Result, String> from = new TableColumn<>("From");
        from.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().from()));
        from.setPrefWidth(280);
        TableColumn<BulkRename.Result, String> to = new TableColumn<>("To");
        to.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().to()));
        to.setPrefWidth(300);
        preview.getColumns().setAll(from, to);
        preview.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        preview.setPrefHeight(240);
        // Flag colliding target names in red so the user cannot commit an ambiguous rename unaware.
        preview.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(BulkRename.Result item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.collision() ? "-fx-text-fill: #e05555;" : "");
            }
        });
    }

    private void recompute() {
        BulkRename.Rule rule = buildRule();
        List<BulkRename.Result> results = BulkRename.preview(names, rule);
        preview.setItems(FXCollections.observableArrayList(results));

        long collisions = results.stream().filter(BulkRename.Result::collision).count();
        long changed = results.stream().filter(r -> !r.from().equals(r.to())).count();
        if (collisions > 0) {
            warning.setText("⚠ " + collisions + " name collision(s) — resolve before renaming");
        } else if (changed == 0) {
            warning.setText("No changes");
        } else {
            warning.setText(changed + " of " + names.size() + " will be renamed");
        }
        setOkDisabled(collisions > 0 || changed == 0);
    }

    /** Disables OK while the plan would collide or do nothing; the button exists only once realised. */
    private void setOkDisabled(boolean disable) {
        if (preview.getScene() != null && preview.getScene().getRoot() instanceof DialogPane dp) {
            javafx.scene.Node ok = dp.lookupButton(ButtonType.OK);
            if (ok != null) ok.setDisable(disable);
        }
    }

    private BulkRename.Rule buildRule() {
        String suf = suffix.getText() == null ? "" : suffix.getText();
        String token = "";
        if (addNumber.isSelected()) {
            token = "{n}";
            suf = suf + token;   // append the number after the base + user suffix
        }
        return new BulkRename.Rule(
                find.getText(), replace.getText(), regex.isSelected(),
                prefix.getText(), suf,
                token, start.getValue(), step.getValue(), pad.getValue(),
                nameCase.getValue());
    }
}
