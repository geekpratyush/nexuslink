package com.nexuslink.ui.ldap;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dialog for entering or editing a directory entry: a DN field plus an attribute editor that uses the
 * familiar {@code name: value} line format (one value per line, repeat a name for multi-valued
 * attributes). Used both to add a child entry (DN editable, prefilled with the parent) and to modify
 * an existing one (DN fixed). Returns the DN and parsed attributes, or empty if cancelled.
 */
final class LdapEntryDialog {

    /** The DN and attribute map the user produced. */
    record Result(String dn, Map<String, List<String>> attributes) {}

    private LdapEntryDialog() {
    }

    /** Add-entry variant: DN editable and prefilled with {@code ,parentDn} for convenience. */
    static Optional<Result> openAdd(Window owner, String parentDn) {
        String seedDn = parentDn == null || parentDn.isBlank() ? "" : "," + parentDn;
        String seedAttrs = "objectClass: top\nobjectClass: \ncn: ";
        return show(owner, "Add entry", seedDn, true, seedAttrs);
    }

    /** Modify-entry variant: DN fixed, attribute editor prefilled with the entry's current values. */
    static Optional<Result> openModify(Window owner, String dn, Map<String, List<String>> current) {
        return show(owner, "Modify entry", dn, false, render(current));
    }

    private static Optional<Result> show(Window owner, String title, String dn, boolean dnEditable,
                                         String attrsText) {
        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title + " — one \"name: value\" per line");
        if (owner != null) dialog.initOwner(owner);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField dnField = new TextField(dn);
        dnField.getStyleClass().add("nl-field");
        dnField.setDisable(!dnEditable);
        dnField.setPromptText("cn=New Entry,ou=people,dc=example,dc=com");

        TextArea attrArea = new TextArea(attrsText);
        attrArea.getStyleClass().add("code-area");
        attrArea.setPrefRowCount(12);
        attrArea.setPromptText("objectClass: inetOrgPerson\ncn: Jane Doe\nsn: Doe\nmail: jane@example.com");
        VBox.setVgrow(attrArea, Priority.ALWAYS);

        VBox content = new VBox(8, new Label("DN:"), dnField, new Label("Attributes:"), attrArea);
        content.setPadding(new Insets(12));
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            return new Result(dnField.getText().trim(), parse(attrArea.getText()));
        });
        return dialog.showAndWait();
    }

    /** Render attributes as {@code name: value} lines (one line per value), insertion-ordered. */
    private static String render(Map<String, List<String>> attributes) {
        StringBuilder sb = new StringBuilder();
        if (attributes != null) {
            for (Map.Entry<String, List<String>> e : attributes.entrySet()) {
                for (String v : e.getValue()) {
                    sb.append(e.getKey()).append(": ").append(v).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Parse {@code name: value} lines into an ordered multimap; blank lines and a leading {@code dn:} ignored. */
    static Map<String, List<String>> parse(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.isEmpty() || name.equalsIgnoreCase("dn") || value.isEmpty()) continue;
            out.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return out;
    }
}
