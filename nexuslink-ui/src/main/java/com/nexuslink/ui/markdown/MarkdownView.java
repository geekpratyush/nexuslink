package com.nexuslink.ui.markdown;

import com.nexuslink.ui.theme.ThemeManager;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders Markdown (GitHub-flavoured, incl. tables) to a themed HTML page inside a JavaFX
 * {@link WebView}. Fenced <code>```mermaid</code> blocks are emitted as Mermaid diagrams, so the
 * same component powers both the Help content and generated ER diagrams.
 *
 * <p>Mermaid.js is loaded from a CDN; diagrams therefore need network access on first render,
 * while plain Markdown renders fully offline.
 */
public final class MarkdownView extends StackPane {

    private static final String MERMAID_CDN =
            "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js";

    private final WebView webView = new WebView();
    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownView() {
        getStyleClass().add("markdown-view");
        webView.setContextMenuEnabled(false);
        getChildren().add(webView);

        List<Extension> exts = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(exts).build();
        this.renderer = HtmlRenderer.builder()
                .extensions(exts)
                .nodeRendererFactory(MermaidCodeRenderer::new)
                .build();
    }

    /** Renders {@code markdown} into the view using the active theme. */
    public void setMarkdown(String markdown) {
        String body = renderer.render(parser.parse(markdown == null ? "" : markdown));
        webView.getEngine().loadContent(page(body));
    }

    /** Convenience for showing a single Mermaid diagram. */
    public void setMermaid(String diagram) {
        setMarkdown("```mermaid\n" + diagram + "\n```\n");
    }

    /** Scrolls the rendered page to the heading whose text matches {@code title}. */
    public void scrollToHeading(String title) {
        String safe = title.replace("\\", "\\\\").replace("'", "\\'");
        try {
            webView.getEngine().executeScript(
                "(function(){var hs=document.querySelectorAll('h1,h2,h3');"
              + "for(var i=0;i<hs.length;i++){if(hs[i].textContent.trim()==='" + safe + "'){"
              + "hs[i].scrollIntoView();return;}}})();");
        } catch (Exception ignored) { /* page not ready */ }
    }

    private String page(String bodyHtml) {
        boolean dark = ThemeManager.get().current() == ThemeManager.Theme.DARK;
        String bg      = dark ? "#0F172A" : "#FFFFFF";
        String fg      = dark ? "#E2E8F0" : "#1E293B";
        String muted   = dark ? "#94A3B8" : "#475569";
        String accent  = dark ? "#22D3EE" : "#0E7490";
        String border  = dark ? "#1E293B" : "#E2E8F0";
        String codeBg  = dark ? "#020617" : "#F1F5F9";
        String mermaidTheme = dark ? "dark" : "default";

        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <style>
              body { background:%s; color:%s; font-family:'Inter','Segoe UI',sans-serif;
                     font-size:14px; line-height:1.6; padding:18px 22px; margin:0; }
              h1,h2,h3 { color:%s; line-height:1.3; }
              h1 { font-size:26px; } h2 { font-size:20px; border-bottom:1px solid %s; padding-bottom:4px; }
              h3 { font-size:16px; }
              a { color:%s; text-decoration:none; } a:hover { text-decoration:underline; }
              p,li { color:%s; }
              code { background:%s; padding:2px 5px; border-radius:4px;
                     font-family:'JetBrains Mono',Consolas,monospace; font-size:13px; }
              pre { background:%s; padding:12px; border-radius:8px; overflow:auto; border:1px solid %s; }
              pre code { background:none; padding:0; }
              table { border-collapse:collapse; margin:10px 0; }
              th,td { border:1px solid %s; padding:6px 12px; } th { background:%s; }
              blockquote { border-left:3px solid %s; margin:8px 0; padding:2px 14px; color:%s; }
              .mermaid { background:%s; border:1px solid %s; border-radius:8px; padding:14px; margin:12px 0; text-align:center; }
            </style></head><body>
            %s
            <script src="%s"></script>
            <script>try{mermaid.initialize({startOnLoad:true,theme:'%s',securityLevel:'loose'});}catch(e){}</script>
            </body></html>
            """.formatted(bg, fg, fg, border, accent, muted, codeBg, codeBg, border,
                          border, codeBg, accent, muted, codeBg, border,
                          bodyHtml, MERMAID_CDN, mermaidTheme);
    }

    /** Emits fenced {@code mermaid} blocks as &lt;div class="mermaid"&gt; (raw, unescaped). */
    private static final class MermaidCodeRenderer implements NodeRenderer {
        private final HtmlWriter html;
        private final HtmlNodeRendererContext context;

        MermaidCodeRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            this.html = context.getWriter();
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() { return Set.of(FencedCodeBlock.class); }

        @Override public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            String info = block.getInfo() == null ? "" : block.getInfo().trim();
            if (info.equalsIgnoreCase("mermaid")) {
                html.line();
                html.tag("div", Map.of("class", "mermaid"));
                html.raw(block.getLiteral());
                html.tag("/div");
                html.line();
            } else {
                html.line();
                html.tag("pre");
                html.tag("code", info.isBlank() ? Map.of() : Map.of("class", "language-" + info.split("\\s")[0]));
                html.text(block.getLiteral());
                html.tag("/code");
                html.tag("/pre");
                html.line();
            }
        }
    }
}
