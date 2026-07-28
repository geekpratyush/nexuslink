package com.nexuslink.ui.mqtt;

import com.nexuslink.protocol.mqtt.MqttHistoryEntry;
import com.nexuslink.protocol.mqtt.MqttHistoryStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the MQTT view reloads its persisted message history and filters it by topic.
 * Runs on a real JavaFX thread; skipped when no toolkit is available.
 */
class MqttViewHistoryTest {

    private static boolean fxUp = false;

    @TempDir
    Path dir;

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
    void reloadsPersistedHistoryAndFiltersByTopic() throws Exception {
        if (!fxUp) return;

        // A previous session's history, already on disk.
        MqttHistoryStore store = new MqttHistoryStore(dir.resolve("mqtt-history.log"));
        store.append(new MqttHistoryEntry(1, MqttHistoryEntry.Direction.RECEIVED,
                "sensors/kitchen/temp", 1, false, "21.0"));
        store.append(new MqttHistoryEntry(2, MqttHistoryEntry.Direction.PUBLISHED,
                "alarms/fire", 0, true, "off"));

        AtomicReference<MqttView> viewRef = new AtomicReference<>();
        AtomicReference<TableView<?>> tableRef = new AtomicReference<>();
        onFx(() -> {
            MqttView view = new MqttView(store);
            new Scene(view);   // realise the scene graph so lookups work
            viewRef.set(view);
            tableRef.set((TableView<?>) view.lookup("#mqtt-history-table"));
        });

        TableView<?> table = tableRef.get();
        assertNotNull(table, "the history table should be in the view");

        // The load is asynchronous — wait for both persisted rows to appear.
        assertTrue(awaitRows(table, 2), "persisted history should be reloaded into the table");

        AtomicReference<AssertionError> failure = new AtomicReference<>();
        onFx(() -> {
            try {
                TextField filter = (TextField) viewRef.get().lookup("#mqtt-history-topic-filter");
                filter.setText("sensors/#");
                assertEquals(1, table.getItems().size(), "wildcard filter should keep only the sensor message");
                assertEquals("sensors/kitchen/temp",
                        ((MqttHistoryEntry) table.getItems().get(0)).topic());

                filter.setText("");
                assertEquals(2, table.getItems().size(), "clearing the filter restores every message");
            } catch (AssertionError e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    private static boolean awaitRows(TableView<?> table, int expected) throws Exception {
        for (int i = 0; i < 50; i++) {
            AtomicReference<Integer> size = new AtomicReference<>(0);
            onFx(() -> size.set(table.getItems().size()));
            if (size.get() == expected) return true;
            Thread.sleep(100);
        }
        return false;
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
