package com.nexuslink.ui.markdown;

import com.nexuslink.ui.theme.ThemeManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.web.WebView;

/**
 * Interactive Mermaid diagram viewer (used for DB ER diagrams). The rendered SVG supports
 * <b>mouse-wheel zoom</b> and <b>drag-to-pan</b> (via svg-pan-zoom), and a toolbar toggles the
 * <b>layout direction</b> (top-down / left-right), the <b>theme</b> (dark/light), and resets the view.
 */
public final class DiagramView extends BorderPane {

    private static final String MERMAID_CDN = "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js";
    private static final String PANZOOM_CDN = "https://cdn.jsdelivr.net/npm/svg-pan-zoom@3.6.1/dist/svg-pan-zoom.min.js";

    private final WebView web = new WebView();
    private String source = "";
    private String direction = "TB";
    private boolean dark = ThemeManager.get().current() == ThemeManager.Theme.DARK;

    public DiagramView() {
        getStyleClass().add("diagram-view");
        web.setContextMenuEnabled(false);
        setTop(buildToolbar());
        setCenter(web);
    }

    /** Sets the Mermaid source (e.g. an {@code erDiagram}) and renders it. */
    public void setDiagram(String mermaidSource) {
        this.source = mermaidSource == null ? "" : mermaidSource;
        render();
    }

    private HBox buildToolbar() {
        ToggleButton tb = new ToggleButton("Top-down");
        ToggleButton lr = new ToggleButton("Left-right");
        javafx.scene.control.ToggleGroup grp = new javafx.scene.control.ToggleGroup();
        tb.setToggleGroup(grp); lr.setToggleGroup(grp);
        tb.setSelected(true);
        tb.getStyleClass().add("btn-secondary");
        lr.getStyleClass().add("btn-secondary");
        tb.setOnAction(e -> { direction = "TB"; render(); });
        lr.setOnAction(e -> { direction = "LR"; render(); });

        Button theme = new Button("Toggle theme");
        theme.getStyleClass().add("btn-secondary");
        theme.setOnAction(e -> { dark = !dark; render(); });

        Button fit = new Button("Fit / reset");
        fit.getStyleClass().add("btn-secondary");
        fit.setOnAction(e -> { try { web.getEngine().executeScript("nlReset()"); } catch (Exception ignored) { } });

        Label hint = new Label("scroll = zoom · drag = pan");
        hint.getStyleClass().add("meta-label");

        HBox bar = new HBox(8, new Label("Layout:"), tb, lr, theme, fit, hint);
        ((Label) bar.getChildren().get(0)).getStyleClass().add("meta-label");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        return bar;
    }

    private void render() {
        String bg = dark ? "#0F172A" : "#FFFFFF";
        String mermaidTheme = dark ? "dark" : "default";
        String diagram = injectDirection(source, direction);
        String html = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><style>
              html,body { margin:0; height:100%%; background:%s; overflow:hidden; }
              #d { width:100%%; height:100%%; }
              #d svg { width:100%%; height:100%%; }
            </style></head><body>
            <div id="d" class="mermaid">%s</div>
            <script src="%s"></script>
            <script src="%s"></script>
            <script>
              mermaid.initialize({startOnLoad:false, theme:'%s', securityLevel:'loose'});
              mermaid.run().then(function(){
                var svg = document.querySelector('#d svg');
                if (svg && window.svgPanZoom) {
                  svg.setAttribute('width','100%%'); svg.setAttribute('height','100%%');
                  window.__pz = svgPanZoom(svg, {zoomEnabled:true, controlIconsEnabled:false,
                      fit:true, center:true, minZoom:0.2, maxZoom:20, mouseWheelZoomEnabled:true});
                }
              }).catch(function(e){ document.body.innerHTML = '<pre style="color:#f55;padding:12px">'+e+'</pre>'; });
              function nlReset(){ if(window.__pz){ window.__pz.resetZoom(); window.__pz.center(); window.__pz.fit(); } }
            </script></body></html>
            """.formatted(bg, diagram, MERMAID_CDN, PANZOOM_CDN, mermaidTheme);
        web.getEngine().loadContent(html);
    }

    /** Inserts a {@code direction} directive after the diagram-type line (Mermaid honours it for
     *  flowcharts, class, state and — in recent versions — ER diagrams). */
    private static String injectDirection(String src, String dir) {
        int nl = src.indexOf('\n');
        if (nl < 0) return src;
        return src.substring(0, nl) + "\n  direction " + dir + src.substring(nl);
    }
}
