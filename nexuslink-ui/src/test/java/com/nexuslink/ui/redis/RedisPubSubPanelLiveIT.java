package com.nexuslink.ui.redis;

import com.nexuslink.protocol.redis.RedisService;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the Pub/Sub panel against a real Redis: subscribe through the panel's own control, publish
 * through the shared service, and assert the message lands in the table.
 *
 * <p>Gated on {@code -Dnexuslink.it=true} with the {@code test-env} stack up
 * ({@code docker compose -f test-env/docker-compose.yml up -d redis}); needs a JavaFX toolkit.
 */
class RedisPubSubPanelLiveIT {

    private static final String URI = System.getProperty("redis.uri", "redis://localhost:6379");

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
        if (fxUp) Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    @Test
    void subscribeThenPublishShowsTheMessage() throws Exception {
        assumeTrue(Boolean.getBoolean("nexuslink.it"), "gated on -Dnexuslink.it=true");
        assumeTrue(fxUp, "needs a JavaFX toolkit");

        RedisService service = new RedisService();
        service.connect(URI);

        AtomicReference<RedisPubSubPanel> panelRef = new AtomicReference<>();
        AtomicReference<TableView<?>> tableRef = new AtomicReference<>();
        onFx(() -> {
            RedisPubSubPanel panel = new RedisPubSubPanel(() -> URI, service);
            new Scene(panel);
            // SplitPane only creates its skin (and thus its child nodes) once CSS + layout ran,
            // so a lookup before this returns nothing.
            panel.applyCss();
            panel.layout();
            panelRef.set(panel);
            tableRef.set((TableView<?>) panel.lookup("#pubsub-messages"));
            TextField target = (TextField) panel.lookup("#pubsub-target");
            target.setText("nexuslink.it.channel");
            target.fireEvent(new javafx.event.ActionEvent());   // same path as pressing Enter
        });

        TableView<?> table = tableRef.get();
        assertNotNull(table, "the message table should be in the panel");

        // The subscribe round-trip is asynchronous; publish until the subscription is live.
        boolean delivered = false;
        for (int attempt = 0; attempt < 40 && !delivered; attempt++) {
            service.publish("nexuslink.it.channel", "hello-" + attempt);
            Thread.sleep(100);
            AtomicReference<Integer> rows = new AtomicReference<>(0);
            onFx(() -> rows.set(table.getItems().size()));
            delivered = rows.get() > 0;
        }
        assertTrue(delivered, "a published message should appear in the panel's table");

        onFx(() -> panelRef.get().dispose());
        service.close();
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS), "FX task did not complete");
    }
}
