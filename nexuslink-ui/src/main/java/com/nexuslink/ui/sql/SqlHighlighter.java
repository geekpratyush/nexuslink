package com.nexuslink.ui.sql;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;

import com.nexuslink.protocol.db.SqlToken;
import com.nexuslink.protocol.db.SqlTokenizer;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Syntax highlighting for the SQL editor, built on a RichTextFX {@link CodeArea}. It reuses the
 * canonical {@link SqlTokenizer} from the DB module (the same lexer the script splitter is built on),
 * mapping each token type to a {@code .sql-*} style class (see {@code theme-base.css}) so statements
 * read clearly in both themes.
 *
 * <p>Highlighting is dialect-agnostic — it covers the common SQL vocabulary shared across
 * PostgreSQL / MySQL / SQLite / SQL Server / Oracle rather than any single dialect's extensions.
 */
public final class SqlHighlighter {

    private SqlHighlighter() { }

    // Built-in functions / types get their own tint; the tokenizer reports these as IDENTIFIERs,
    // so we classify them here on top of its keyword recognition.
    private static final java.util.Set<String> FUNCTIONS = java.util.Set.of(
        "count","sum","avg","min","max","coalesce","nullif","greatest","least","round","floor",
        "ceil","abs","length","char_length","lower","upper","trim","ltrim","rtrim","substring",
        "substr","replace","concat","concat_ws","now","current_date","current_timestamp","extract",
        "date_trunc","to_char","to_date","to_timestamp","json_agg","array_agg","string_agg",
        "row_number","rank","dense_rank");
    private static final java.util.Set<String> TYPES = java.util.Set.of(
        "int","integer","bigint","smallint","tinyint","serial","bigserial","decimal","numeric",
        "real","double","float","money","char","varchar","varchar2","text","clob","boolean","bool",
        "date","time","timestamp","timestamptz","datetime","interval","uuid","json","jsonb","bytea",
        "blob","binary","xml");

    /** Creates a CodeArea wired for SQL highlighting; re-highlights on every edit. */
    public static CodeArea area() {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("code-area", "code-sql");
        area.textProperty().addListener((o, ov, nv) -> area.setStyleSpans(0, compute(nv)));
        return area;
    }

    static StyleSpans<Collection<String>> compute(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        if (text == null || text.isEmpty()) {
            spans.add(Collections.emptyList(), 0);
            return spans.create();
        }
        // Tokens are contiguous and cover the whole input, so we can add one span per token.
        for (SqlToken tok : SqlTokenizer.tokenize(text)) {
            String cls = styleFor(tok);
            spans.add(cls == null ? Collections.emptyList() : Collections.singleton(cls), tok.length());
        }
        return spans.create();
    }

    private static String styleFor(SqlToken tok) {
        return switch (tok.type()) {
            case KEYWORD -> "sql-keyword";
            case STRING -> "sql-string";
            case NUMBER -> "sql-number";
            case LINE_COMMENT, BLOCK_COMMENT -> "sql-comment";
            case OPERATOR -> "sql-punct";
            case PARAMETER -> "sql-function";
            case IDENTIFIER -> {
                String w = tok.text().toLowerCase(Locale.ROOT);
                yield FUNCTIONS.contains(w) ? "sql-function" : TYPES.contains(w) ? "sql-type" : null;
            }
            default -> null;
        };
    }

    /** Keyword + built-in function words, upper-cased, for the completion popup. */
    public static java.util.List<String> vocabulary() {
        java.util.List<String> all = new java.util.ArrayList<>(SqlTokenizer.keywords());
        for (String f : FUNCTIONS) all.add(f.toUpperCase(Locale.ROOT));
        return all;
    }
}
