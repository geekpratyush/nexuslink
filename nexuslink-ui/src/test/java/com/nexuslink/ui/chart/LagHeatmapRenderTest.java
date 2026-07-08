package com.nexuslink.ui.chart;

import com.nexuslink.protocol.kafka.ConsumerLagCalculator;
import javafx.application.Platform;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the lag heatmap on a real JavaFX thread and checks the grid gets populated. Skipped
 * automatically when no toolkit/display is available (headless CI).
 */
class LagHeatmapRenderTest {

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

    @Test
    void populatesGridForTopicsAndPartitions() throws Exception {
        if (!fxUp) return;

        AtomicReference<AssertionError> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                LagHeatmap heatmap = new LagHeatmap();
                GridPane grid = (GridPane) ((javafx.scene.control.ScrollPane) heatmap.getCenter()).getContent();

                heatmap.setData(List.of(
                        new ConsumerLagCalculator.LagRow("g", "orders", 0, 10, 15, 5),
                        new ConsumerLagCalculator.LagRow("g", "orders", 1, 10, 110, 100),
                        new ConsumerLagCalculator.LagRow("g", "events", 0, 0, 3, 3)));

                // 2 topics + 1 header row = 3 rows; 2 partitions + 1 header col = 3 cols → up to 9 cells,
                // but the sparse (events,P1) cell is still emitted as a placeholder → exactly 9.
                assertEquals(9, grid.getChildren().size());

                // empty data clears the grid
                heatmap.setData(List.of());
                assertTrue(grid.getChildren().isEmpty());
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
