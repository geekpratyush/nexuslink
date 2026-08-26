package com.nexuslink.ui.connection;

import com.nexuslink.core.connection.ConnectionProfile;
import com.nexuslink.core.connection.ConnectionStore;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The sidebar's connection search box. Skipped when no JavaFX toolkit is available. */
class ConnectionsPanelSearchTest {

    private static boolean fxUp = false;

    @TempDir Path tempDir;

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
    void typingNarrowsTheSavedConnectionsToTheMatches() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionsPanel panel = panelWith(
                    new ConnectionProfile("Orders DB", ConnectionProfile.Protocol.SQL,
                            "jdbc:postgresql://db.internal:5432/orders"),
                    new ConnectionProfile("Events", ConnectionProfile.Protocol.KAFKA, "broker:9092"),
                    new ConnectionProfile("Cache", ConnectionProfile.Protocol.REDIS, "redis://localhost"));

            assertEquals(3, savedNames(panel).size(), "everything shows with an empty box");

            search(panel).setText("kafka");
            assertEquals(List.of("Events"), savedNames(panel), "matches the protocol, not just the name");

            search(panel).setText("5432");
            assertEquals(List.of("Orders DB"), savedNames(panel), "the target is searchable too");

            search(panel).setText("");
            assertEquals(3, savedNames(panel).size(), "clearing the box restores everything");
        });
    }

    @Test
    void aSearchWithNoMatchesShowsAHintRatherThanAnEmptyGroup() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionsPanel panel = panelWith(
                    new ConnectionProfile("Events", ConnectionProfile.Protocol.KAFKA, "broker:9092"));
            search(panel).setText("nothing-like-this");
            assertTrue(savedNames(panel).isEmpty());
            assertTrue(groupText(panel, 0).contains("(0)"), "the header carries the match count");
        });
    }

    @Test
    void theBestMatchIsSelectedSoEnterOpensIt() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionsPanel panel = panelWith(
                    new ConnectionProfile("Staging broker", ConnectionProfile.Protocol.KAFKA, "s:9092"),
                    new ConnectionProfile("Kafka prod", ConnectionProfile.Protocol.KAFKA, "p:9092"));
            search(panel).setText("kafka");

            AtomicReference<ConnectionProfile> opened = new AtomicReference<>();
            panel.setOnOpen(opened::set);
            TreeView<Object> tree = tree(panel);
            Object selected = tree.getSelectionModel().getSelectedItem().getValue();
            assertInstanceOf(ConnectionProfile.class, selected);
            assertEquals("Kafka prod", ((ConnectionProfile) selected).name,
                    "a name starting with the query outranks one that merely contains it");
        });
    }

    // ---- helpers ---------------------------------------------------------------------------

    private ConnectionsPanel panelWith(ConnectionProfile... profiles) {
        ConnectionStore store = new ConnectionStore(tempDir.resolve("connections.json"));
        for (ConnectionProfile p : profiles) store.save(p);
        return new ConnectionsPanel(store);
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(ConnectionsPanel panel) {
        return (TreeView<Object>) panel.getChildren().stream()
                .filter(n -> n instanceof TreeView).findFirst().orElseThrow();
    }

    private static TextField search(ConnectionsPanel panel) {
        return (TextField) panel.lookup(".nl-field");
    }

    /** The names of the profiles currently listed under "Saved". */
    private static List<String> savedNames(ConnectionsPanel panel) {
        List<String> names = new ArrayList<>();
        for (TreeItem<Object> leaf : tree(panel).getRoot().getChildren().get(0).getChildren()) {
            if (leaf.getValue() instanceof ConnectionProfile p) names.add(p.name);
        }
        return names;
    }

    private static String groupText(ConnectionsPanel panel, int index) {
        return String.valueOf(tree(panel).getRoot().getChildren().get(index).getValue());
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); } catch (Throwable t) { failure.set(t); } finally { done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX task did not complete");
        if (failure.get() instanceof AssertionError e) throw e;
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
