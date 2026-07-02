package com.nexuslink.ui.markdown;

import com.nexuslink.ui.theme.ThemeManager;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

        Button svg = new Button("Export SVG");
        svg.getStyleClass().add("btn-secondary");
        svg.setTooltip(new javafx.scene.control.Tooltip("Save the diagram as a scalable vector image"));
        svg.setOnAction(e -> exportSvg());

        Button png = new Button("Export PNG");
        png.getStyleClass().add("btn-secondary");
        png.setTooltip(new javafx.scene.control.Tooltip("Save the current view as a PNG raster image"));
        png.setOnAction(e -> exportPng());

        Label hint = new Label("scroll = zoom · drag = pan");
        hint.getStyleClass().add("meta-label");

        HBox bar = new HBox(8, new Label("Layout:"), tb, lr, theme, fit, svg, png, hint);
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

    /** Serializes the rendered SVG straight from the DOM and writes it to a chosen file. */
    private void exportSvg() {
        Object result;
        try {
            result = web.getEngine().executeScript(
                    "(function(){var s=document.querySelector('#d svg');"
                    + "return s?new XMLSerializer().serializeToString(s):null;})()");
        } catch (Exception ex) { result = null; }
        if (!(result instanceof String svg) || svg.isBlank()) return;
        File file = chooseSave("diagram.svg", "SVG image", "*.svg");
        if (file == null) return;
        String doc = svg.startsWith("<?xml")
                ? svg
                : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" + svg;
        try {
            Files.write(file.toPath(), doc.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** Snapshots the currently-visible diagram to a PNG (use Fit / reset first for the whole diagram). */
    private void exportPng() {
        File file = chooseSave("diagram.png", "PNG image", "*.png");
        if (file == null) return;
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(dark ? Color.web("#0F172A") : Color.WHITE);
        WritableImage image = web.snapshot(params, null);
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        } catch (Exception ignored) { }
    }

    private File chooseSave(String suggestedName, String desc, String ext) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(suggestedName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, ext));
        return chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    }

    /** Inserts a {@code direction} directive after the diagram-type line (Mermaid honours it for
     *  flowcharts, class, state and — in recent versions — ER diagrams). */
    private static String injectDirection(String src, String dir) {
        int nl = src.indexOf('\n');
        if (nl < 0) return src;
        return src.substring(0, nl) + "\n  direction " + dir + src.substring(nl);
    }
}
