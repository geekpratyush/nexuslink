package com.nexuslink.ui.env;

import com.nexuslink.core.env.Environment;
import com.nexuslink.core.env.EnvVariable;
import com.nexuslink.core.env.EnvironmentService;
import com.nexuslink.core.env.SecretMaskingFilter;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Environment Manager tab — create named variable sets (dev / staging / prod), edit their
 * {@code ${VAR}} values, flag secrets (masked until revealed), and choose the active environment
 * that the rest of the app interpolates against. Backed by {@link EnvironmentService}.
 */
public final class EnvironmentManagerView extends BorderPane {

    private final EnvironmentService service;
    private Consumer<String> logger = s -> {};

    private final ListView<Environment> envList = new ListView<>();
    private final TableView<EnvVariable> varTable = new TableView<>();
    private final ObservableList<EnvVariable> vars = FXCollections.observableArrayList();
    private final Label activeLabel = new Label();
    private final CheckBox revealSecrets = new CheckBox("Reveal secrets");

    public EnvironmentManagerView(EnvironmentService service) {
        this.service = service;
        getStyleClass().add("env-view");
        setTop(buildToolbar());
        setCenter(buildBody());
        refreshEnvList();
        if (!envList.getItems().isEmpty()) envList.getSelectionModel().select(0);
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger == null ? s -> {} : logger;
    }

    private VBox buildToolbar() {
        Button addEnv = new Button("New Environment…");
        addEnv.getStyleClass().add("btn-primary");
        addEnv.setOnAction(e -> addEnvironment());

        Button deleteEnv = new Button("Delete Environment");
        deleteEnv.getStyleClass().add("btn-secondary");
        deleteEnv.setOnAction(e -> deleteEnvironment());

        Button setActive = new Button("Set Active");
        setActive.getStyleClass().add("btn-secondary");
        setActive.setOnAction(e -> setActiveEnvironment());

        Button help = new Button("?");
        help.getStyleClass().add("btn-secondary");
        help.setOnAction(e -> com.nexuslink.ui.help.HelpDialog.open("environment-vars"));

        HBox row = new HBox(8, addEnv, deleteEnv, setActive,
                new Separator(javafx.geometry.Orientation.VERTICAL), help);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10));

        activeLabel.getStyleClass().add("meta-label");
        HBox statusRow = new HBox(activeLabel);
        statusRow.setPadding(new Insets(0, 10, 6, 10));
        return new VBox(row, statusRow);
    }

    private SplitPane buildBody() {
        envList.setMinWidth(200);
        envList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Environment env, boolean empty) {
                super.updateItem(env, empty);
                if (empty || env == null) { setText(null); return; }
                boolean isActive = service.active().map(a -> a.id.equals(env.id)).orElse(false);
                setText(isActive ? env.name + "  ● active" : env.name);
            }
        });
        envList.getSelectionModel().selectedItemProperty().addListener((o, ov, env) -> showVariables(env));

        buildVarTable();

        VBox right = new VBox(8, buildVarButtons(), varTable);
        right.setPadding(new Insets(10));
        VBox.setVgrow(varTable, Priority.ALWAYS);

        SplitPane sp = new SplitPane(envList, right);
        sp.setDividerPositions(0.28);
        return sp;
    }

    private HBox buildVarButtons() {
        Button addVar = new Button("Add Variable");
        addVar.getStyleClass().add("btn-secondary");
        addVar.setOnAction(e -> addVariable());

        Button removeVar = new Button("Remove");
        removeVar.getStyleClass().add("btn-secondary");
        removeVar.setOnAction(e -> removeVariable());

        Button save = new Button("Save");
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> saveCurrent());

        revealSecrets.setOnAction(e -> varTable.refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, addVar, removeVar, save, spacer, revealSecrets);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    @SuppressWarnings("unchecked")
    private void buildVarTable() {
        varTable.setEditable(true);
        varTable.setItems(vars);
        varTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        varTable.setPlaceholder(new Label("No variables — add one, then Save."));

        TableColumn<EnvVariable, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().name));
        nameCol.setCellFactory(TextFieldTableCell.forTableColumn());
        nameCol.setOnEditCommit(ev -> {
            EnvVariable v = ev.getRowValue();
            v.name = ev.getNewValue() == null ? "" : ev.getNewValue().trim();
            if (SecretMaskingFilter.looksSecret(v.name)) v.secret = true;   // auto-flag obvious secrets
            varTable.refresh();
        });

        TableColumn<EnvVariable, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().value));
        valueCol.setCellFactory(col -> new MaskableValueCell());
        valueCol.setOnEditCommit(ev -> ev.getRowValue().value = ev.getNewValue() == null ? "" : ev.getNewValue());

        TableColumn<EnvVariable, Boolean> secretCol = new TableColumn<>("Secret");
        secretCol.setCellValueFactory(c -> {
            EnvVariable v = c.getValue();
            SimpleBooleanProperty p = new SimpleBooleanProperty(v.secret);
            p.addListener((o, ov, nv) -> { v.secret = nv; varTable.refresh(); });
            return p;
        });
        secretCol.setCellFactory(CheckBoxTableCell.forTableColumn(secretCol));
        secretCol.setEditable(true);
        secretCol.setMaxWidth(90);

        varTable.getColumns().setAll(nameCol, valueCol, secretCol);
    }

    /** A value cell that shows the mask for secret rows until "Reveal secrets" is ticked. */
    private final class MaskableValueCell extends TextFieldTableCell<EnvVariable, String> {
        MaskableValueCell() { super(new javafx.util.converter.DefaultStringConverter()); }

        @Override public void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); return; }
            EnvVariable v = getTableRow() == null ? null : getTableRow().getItem();
            if (!isEditing() && v != null && v.secret && !revealSecrets.isSelected()) {
                setText(item.isEmpty() ? "" : SecretMaskingFilter.MASK);
            }
        }
    }

    // ---- actions ------------------------------------------------------------

    private void addEnvironment() {
        TextInputDialog dlg = new TextInputDialog("dev");
        dlg.setTitle("New Environment");
        dlg.setHeaderText("Name the environment (e.g. dev, staging, prod)");
        dlg.setContentText("Name:");
        Optional<String> name = dlg.showAndWait();
        name.map(String::trim).filter(s -> !s.isEmpty()).ifPresent(n -> {
            Environment env = service.save(new Environment(n));
            refreshEnvList();
            envList.getSelectionModel().select(env);
            log("Created environment '" + n + "'.");
        });
    }

    private void deleteEnvironment() {
        Environment env = envList.getSelectionModel().getSelectedItem();
        if (env == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete environment '" + env.name + "'?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
            service.delete(env.id);
            refreshEnvList();
            if (!envList.getItems().isEmpty()) envList.getSelectionModel().select(0);
            else vars.clear();
            log("Deleted environment '" + env.name + "'.");
        }
    }

    private void setActiveEnvironment() {
        Environment env = envList.getSelectionModel().getSelectedItem();
        if (env == null) return;
        service.setActive(env.id);
        refreshEnvList();
        updateActiveLabel();
        log("Active environment is now '" + env.name + "'.");
    }

    private void addVariable() {
        if (envList.getSelectionModel().getSelectedItem() == null) return;
        vars.add(new EnvVariable("NEW_VAR", ""));
        varTable.getSelectionModel().selectLast();
        varTable.edit(vars.size() - 1, varTable.getColumns().get(0));
    }

    private void removeVariable() {
        EnvVariable v = varTable.getSelectionModel().getSelectedItem();
        if (v != null) vars.remove(v);
    }

    private void saveCurrent() {
        Environment env = envList.getSelectionModel().getSelectedItem();
        if (env == null) return;
        env.variables.clear();
        env.variables.addAll(vars);
        service.save(env);
        log("Saved environment '" + env.name + "' (" + vars.size() + " variable(s)).");
    }

    // ---- helpers ------------------------------------------------------------

    private void refreshEnvList() {
        Environment selected = envList.getSelectionModel().getSelectedItem();
        envList.getItems().setAll(service.environments());
        if (selected != null) {
            service.byId(selected.id).ifPresent(e -> envList.getSelectionModel().select(e));
        }
        updateActiveLabel();
    }

    private void showVariables(Environment env) {
        vars.clear();
        if (env != null) {
            for (EnvVariable v : env.variables) vars.add(new EnvVariable(v.name, v.value, v.secret));
        }
    }

    private void updateActiveLabel() {
        activeLabel.setText(service.active()
                .map(a -> "Active environment: " + a.name + " — referenced as ${VAR} across the app")
                .orElse("No active environment — ${VAR} resolves from .env / system only"));
    }

    private void log(String message) {
        logger.accept(message);
    }
}
