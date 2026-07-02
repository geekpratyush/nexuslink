package com.nexuslink.ui.util;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * A themed, syntax-highlighting JSON viewer/editor built on a RichTextFX {@link CodeArea}. Keys,
 * strings, numbers, booleans, {@code null} and punctuation each get their own {@code .json-*} style
 * class (see {@code theme-base.css}), so documents are readable in both dark and light themes.
 */
public final class JsonView {

    private JsonView() { }

    private static final Pattern JSON = Pattern.compile(
            "(?<KEY>\"(?:[^\"\\\\]|\\\\.)*\")(?=\\s*:)"
          + "|(?<STRING>\"(?:[^\"\\\\]|\\\\.)*\")"
          + "|(?<NUMBER>-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
          + "|(?<BOOL>\\btrue\\b|\\bfalse\\b)"
          + "|(?<NULL>\\bnull\\b)"
          + "|(?<PUNCT>[{}\\[\\],:])");

    /** Creates a CodeArea wired for JSON highlighting. Highlighting refreshes on every edit. */
    public static CodeArea area(boolean editable) {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("code-area", "code-json");
        area.setEditable(editable);
        area.textProperty().addListener((o, ov, nv) -> area.setStyleSpans(0, computeHighlighting(nv)));
        return area;
    }

    /** Replaces the content and re-highlights it. */
    public static void setText(CodeArea area, String text) {
        area.replaceText(text == null ? "" : text);
        area.moveTo(0);
        area.requestFollowCaret();
    }

    /**
     * A CodeArea styled for JSON but WITHOUT the auto-highlight listener — the caller decides when
     * (and whether) to highlight, via {@link #setSmart}. Use this where the same area also shows
     * non-JSON content (XML, hex dumps, plain text).
     */
    public static CodeArea plainArea(boolean editable) {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("code-area", "code-json");
        area.setEditable(editable);
        return area;
    }

    /**
     * Sets the text and highlights it as JSON only when it looks like JSON (first non-space char is
     * {@code &#123;} or {@code [}); otherwise renders it as plain text. Safe for a viewer that also
     * shows XML / hex / error messages.
     */
    public static void setSmart(CodeArea area, String text) {
        String t = text == null ? "" : text;
        area.replaceText(t);
        area.moveTo(0);
        area.requestFollowCaret();
        String head = t.stripLeading();
        if (!head.isEmpty() && (head.charAt(0) == '{' || head.charAt(0) == '[')) {
            area.setStyleSpans(0, computeHighlighting(t));
        } else {
            StyleSpansBuilder<Collection<String>> plain = new StyleSpansBuilder<>();
            plain.add(Collections.emptyList(), t.length());
            area.setStyleSpans(0, plain.create());
        }
    }

    static StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spans.add(Collections.emptyList(), 0);
            return spans.create();
        }
        Matcher m = JSON.matcher(text);
        int last = 0;
        while (m.find()) {
            String cls =
                    m.group("KEY")    != null ? "json-key"    :
                    m.group("STRING") != null ? "json-string" :
                    m.group("NUMBER") != null ? "json-number" :
                    m.group("BOOL")   != null ? "json-bool"   :
                    m.group("NULL")   != null ? "json-null"   :
                    m.group("PUNCT")  != null ? "json-punct"  : null;
            spans.add(Collections.emptyList(), m.start() - last);
            spans.add(cls == null ? Collections.emptyList() : Collections.singleton(cls),
                    m.end() - m.start());
            last = m.end();
        }
        spans.add(Collections.emptyList(), text.length() - last);
        return spans.create();
    }
}
