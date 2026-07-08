package com.nexuslink.ui.trace;

import com.nexuslink.protocol.http.rest.SpanTree;
import com.nexuslink.protocol.http.rest.ZipkinSpanExporter;
import javafx.application.Platform;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders the trace tree on a real JavaFX thread; skipped when no toolkit is available. */
class TraceTreeViewTest {

    private static boolean fxUp = false;

    @BeforeAll
    static void startFx() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            fxUp = latch.await(5, TimeUnit.SECONDS);
        } catch (IllegalStateException already) {
            fxUp = true;
        } catch (Throwable t) {
            fxUp = false;
        }
    }

    private static ZipkinSpanExporter.Span span(String id, String parent) {
        return new ZipkinSpanExporter.Span("t1", id, parent, "GET /" + id,
                ZipkinSpanExporter.Kind.CLIENT, 10, 2000, "nexuslink",
                Map.of("http.status_code", "200"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsNestedTreeItems() throws Exception {
        if (!fxUp) return;
        AtomicReference<AssertionError> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                TraceTreeView view = new TraceTreeView();
                TreeView<SpanTree.Node> tree =
                        (TreeView<SpanTree.Node>) view.getChildrenUnmodifiable().stream()
                                .filter(n -> n instanceof TreeView).findFirst().orElseThrow();

                view.setSpans(List.of(span("root", null), span("child", "root")));

                assertTrue(tree.getRoot() != null);
                assertEquals(1, tree.getRoot().getChildren().size());       // one root span
                var rootItem = tree.getRoot().getChildren().get(0);
                assertEquals("root", rootItem.getValue().span().id());
                assertEquals(1, rootItem.getChildren().size());             // one nested child
                assertEquals("child", rootItem.getChildren().get(0).getValue().span().id());

                view.setSpans(List.of());
                assertTrue(tree.getRoot().getChildren().isEmpty());
            } catch (AssertionError e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX task did not complete");
        if (failure.get() != null) throw failure.get();
    }
}
