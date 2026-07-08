package com.nexuslink.ui.trace;

import com.nexuslink.protocol.http.rest.SpanTree;
import com.nexuslink.protocol.http.rest.ZipkinSpanExporter;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;

import java.util.List;

/**
 * Distributed-trace tree view (§9.2): renders the spans captured during a REST session as an
 * expandable parent→child tree, grouped by trace, each node showing its name, duration and HTTP
 * status. The tree shape comes from the pure {@link SpanTree}; this class only renders it.
 */
public final class TraceTreeView extends BorderPane {

    private final TreeView<SpanTree.Node> tree = new TreeView<>();
    private final Label summary = new Label("No trace captured yet.");

    public TraceTreeView() {
        getStyleClass().add("trace-tree");
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new SpanCell());

        summary.getStyleClass().add("meta-label");
        BorderPane.setMargin(summary, new Insets(6, 8, 6, 8));
        setTop(summary);
        setCenter(tree);
    }

    /** Rebuilds the tree from the captured spans. Must run on the FX thread. */
    public void setSpans(List<ZipkinSpanExporter.Span> spans) {
        List<SpanTree.Node> forest = SpanTree.build(spans);
        TreeItem<SpanTree.Node> root = new TreeItem<>(null);
        for (SpanTree.Node n : forest) root.getChildren().add(toItem(n));
        tree.setRoot(root);

        int total = spans == null ? 0 : spans.size();
        long traces = forest.stream().map(SpanTree.Node::traceId).distinct().count();
        summary.setText(total == 0 ? "No trace captured yet."
                : total + " span(s) across " + traces + " trace(s)");
    }

    private static TreeItem<SpanTree.Node> toItem(SpanTree.Node node) {
        TreeItem<SpanTree.Node> item = new TreeItem<>(node);
        item.setExpanded(true);
        for (SpanTree.Node child : node.children()) item.getChildren().add(toItem(child));
        return item;
    }

    /** Renders one span row: name · duration · status. */
    private static final class SpanCell extends TreeCell<SpanTree.Node> {
        @Override protected void updateItem(SpanTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                return;
            }
            ZipkinSpanExporter.Span s = node.span();
            double ms = s.durationMicros() / 1000.0;
            String status = s.tags() == null ? null : s.tags().get("http.status_code");
            StringBuilder sb = new StringBuilder(s.name() == null ? "(span)" : s.name());
            sb.append("   ").append(String.format("%.1f ms", ms));
            if (status != null && !status.isBlank()) sb.append("   [").append(status).append(']');
            setText(sb.toString());
        }
    }
}
