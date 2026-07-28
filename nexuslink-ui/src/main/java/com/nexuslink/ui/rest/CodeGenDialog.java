package com.nexuslink.ui.rest;

import com.nexuslink.plugin.codegen.CodeGenRegistry;
import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;
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

import java.util.List;

/**
 * Non-modal "generate client code" window: pick a language, view the snippet, copy it.
 *
 * <p>Protocol-agnostic — the languages on offer come from whichever {@link CodeGenerator} in the
 * {@link CodeGenRegistry} claims the request object, so any protocol that ships a generator gets
 * this window for free. When several generators claim the same request, a protocol dropdown appears
 * alongside the language one.
 */
public final class CodeGenDialog {

    private CodeGenDialog() {}

    /** Opens the window for {@code request}, or does nothing when no generator handles it. */
    public static void show(Window owner, Object request) {
        show(owner, request, CodeGenRegistry.global());
    }

    /** Test seam: renders against an explicit registry rather than the shared one. */
    public static void show(Window owner, Object request, CodeGenRegistry registry) {
        List<CodeGenerator> generators = registry.generatorsFor(request);
        if (generators.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "No code generator is registered for this request type.");
            alert.setHeaderText("Nothing to generate");
            if (owner != null) alert.initOwner(owner);
            alert.showAndWait();
            return;
        }

        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle("Generate code");

        ComboBox<CodeGenerator> protocolCombo =
                new ComboBox<>(FXCollections.observableArrayList(generators));
        protocolCombo.setValue(generators.get(0));
        protocolCombo.setConverter(new StringConverter<>() {
            @Override public String toString(CodeGenerator g) { return g == null ? "" : g.displayName(); }
            @Override public CodeGenerator fromString(String s) { return null; }
        });

        ComboBox<CodeGenTarget> targetCombo = new ComboBox<>();
        targetCombo.setConverter(new StringConverter<>() {
            @Override public String toString(CodeGenTarget t) { return t == null ? "" : t.label(); }
            @Override public CodeGenTarget fromString(String s) { return null; }
        });

        TextArea code = new TextArea();
        code.setId("codegen-output");
        code.setEditable(false);
        code.getStyleClass().add("code-area");
        code.setWrapText(false);

        Button copy = new Button("Copy");
        copy.getStyleClass().add("btn-primary");

        Label copied = new Label();
        copied.getStyleClass().add("meta-label");

        Runnable regen = () -> {
            CodeGenerator generator = protocolCombo.getValue();
            CodeGenTarget target = targetCombo.getValue();
            if (generator == null || target == null) return;
            try {
                code.setText(generator.generate(target, request));
            } catch (RuntimeException e) {
                code.setText("Could not generate a snippet: " + e.getMessage());
            }
            copied.setText("");
        };

        protocolCombo.valueProperty().addListener((o, a, generator) -> {
            targetCombo.setItems(FXCollections.observableArrayList(generator.targets()));
            targetCombo.setValue(generator.targets().get(0));   // fires regen through the listener below
        });
        targetCombo.valueProperty().addListener((o, a, b) -> regen.run());

        // Prime the target list for the initially selected protocol.
        CodeGenerator first = generators.get(0);
        targetCombo.setItems(FXCollections.observableArrayList(first.targets()));
        targetCombo.setValue(first.targets().get(0));
        regen.run();

        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(code.getText());
            Clipboard.getSystemClipboard().setContent(content);
            copied.setText("Copied ✓");
        });

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        if (generators.size() > 1) {
            top.getChildren().addAll(metaLabel("Protocol:"), protocolCombo);
        }
        top.getChildren().addAll(metaLabel("Language:"), targetCombo, copy, copied);

        VBox root = new VBox(10, top, code);
        root.getStyleClass().add("root");
        root.setPadding(new Insets(12));
        VBox.setVgrow(code, Priority.ALWAYS);

        Scene scene = new Scene(root, 660, 480);
        ThemeManager.get().register(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static Label metaLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("meta-label");
        return label;
    }
}
