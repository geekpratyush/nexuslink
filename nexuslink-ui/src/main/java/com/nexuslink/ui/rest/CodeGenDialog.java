package com.nexuslink.ui.rest;

import com.nexuslink.protocol.http.rest.RestCodeGenerator;
import com.nexuslink.protocol.http.rest.RestCodeGenerator.Language;
import com.nexuslink.protocol.http.rest.RestRequest;
import com.nexuslink.ui.theme.ThemeManager;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Non-modal "generate client code" window: pick a language, view the snippet, copy it. */
public final class CodeGenDialog {

    private CodeGenDialog() {}

    public static void show(Window owner, RestRequest request) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Generate code");

        ComboBox<Language> langCombo = new ComboBox<>(FXCollections.observableArrayList(Language.values()));
        langCombo.setValue(Language.CURL);
        langCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Language l) { return l == null ? "" : l.label(); }
            @Override public Language fromString(String s) { return null; }
        });

        TextArea code = new TextArea();
        code.setEditable(false);
        code.getStyleClass().add("code-area");
        code.setWrapText(false);

        Button copy = new Button("Copy");
        copy.getStyleClass().add("btn-primary");

        Label copied = new Label();
        copied.getStyleClass().add("meta-label");

        Runnable regen = () -> code.setText(RestCodeGenerator.generate(langCombo.getValue(), request));
        langCombo.valueProperty().addListener((o, a, b) -> { regen.run(); copied.setText(""); });
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(code.getText());
            Clipboard.getSystemClipboard().setContent(content);
            copied.setText("Copied ✓");
        });
        regen.run();

        Label langLbl = new Label("Language:");
        langLbl.getStyleClass().add("meta-label");
        HBox top = new HBox(8, langLbl, langCombo, copy, copied);
        top.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, top, code);
        root.getStyleClass().add("root");
        root.setPadding(new Insets(12));
        VBox.setVgrow(code, Priority.ALWAYS);

        Scene scene = new Scene(root, 660, 480);
        ThemeManager.get().register(scene);
        stage.setScene(scene);
        stage.show();
    }
}
