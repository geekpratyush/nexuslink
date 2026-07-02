package com.nexuslink.ui.util;

import java.util.Locale;

import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.paint.Color;

/**
 * Shared styling for HTTP verbs so GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS look the same everywhere
 * (REST client, history, generated code, MCP/AI testers). Colours come from the {@code .method-*}
 * classes in {@code theme-base.css}, so they follow the active dark/light theme automatically.
 */
public final class HttpMethods {

    private HttpMethods() { }

    /** True if the token is a recognised HTTP verb (case-insensitive). */
    public static boolean isMethod(String token) {
        if (token == null) return false;
        return switch (token.trim().toUpperCase(Locale.ROOT)) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> true;
            default -> false;
        };
    }

    /** The style class for a verb, e.g. {@code "method-get"}; falls back to {@code "method-get"}. */
    public static String styleClass(String method) {
        if (method == null || method.isBlank()) return "method-get";
        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "GET"     -> "method-get";
            case "POST"    -> "method-post";
            case "PUT"     -> "method-put";
            case "PATCH"   -> "method-patch";
            case "DELETE"  -> "method-delete";
            case "HEAD"    -> "method-head";
            case "OPTIONS" -> "method-options";
            default        -> "method-get";
        };
    }

    /** A coloured, monospaced pill Label showing the verb — handy in list rows and headers. */
    public static Label badge(String method) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        Label label = new Label(m);
        label.getStyleClass().addAll("method-badge", styleClass(m));
        // Tint the chip border with the verb's own text colour once it is laid out.
        label.skinProperty().addListener((o, ov, nv) -> tintBorder(label));
        tintBorder(label);
        return label;
    }

    private static void tintBorder(Label label) {
        Color c = (Color) label.getTextFill();
        if (c != null) {
            label.setStyle("-fx-border-color: rgba(%d,%d,%d,0.55);".formatted(
                    (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255)));
        }
    }

    /**
     * Colours a method {@link ComboBox}: the shown value and every dropdown row are tinted by verb.
     * Call once after building the combo.
     */
    public static void styleCombo(ComboBox<String> combo) {
        combo.setButtonCell(methodCell());
        combo.setCellFactory(list -> methodCell());
    }

    private static ListCell<String> methodCell() {
        return new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(s -> s.startsWith("method-"));
                if (empty || item == null) { setText(null); return; }
                setText(item);
                getStyleClass().add(styleClass(item));
            }
        };
    }
}
