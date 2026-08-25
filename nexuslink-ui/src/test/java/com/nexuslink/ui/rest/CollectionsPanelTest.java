package com.nexuslink.ui.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuslink.protocol.http.rest.CollectionNode;
import com.nexuslink.protocol.http.rest.RestCollectionStore;
import com.nexuslink.protocol.http.rest.RestCollectionTree;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The collections sidebar mirrors the stored tree and writes every change straight through to the
 * store. Runs on a real JavaFX thread; skipped when no toolkit is available.
 */
class CollectionsPanelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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
        // Closing the last window would otherwise shut the toolkit down and strand the next test.
        if (fxUp) Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    private RestCollectionStore seeded() {
        RestCollectionStore store = new RestCollectionStore(dir.resolve("rest-collections.json"));
        CollectionNode api = CollectionNode.folder("API");
        api.children.add(CollectionNode.request("Health",
                JSON.createObjectNode().put("method", "GET").put("url", "https://api/health")));
        api.children.add(CollectionNode.folder("Admin"));
        store.collections().add(api);
        return store;
    }

    private <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "FX work did not run");
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private TreeView<CollectionNode> treeOf(CollectionsPanel panel) {
        return (TreeView<CollectionNode>) panel.lookup("#restCollectionsTree");
    }

    @Test
    void treeMirrorsTheStoredForest() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        RestCollectionStore store = seeded();
        TreeItem<CollectionNode> root = onFx(() -> {
            CollectionsPanel panel = new CollectionsPanel(store, () -> "{}", j -> {}, s -> {});
            return treeOf(panel).getRoot();
        });
        assertEquals(1, root.getChildren().size());
        TreeItem<CollectionNode> api = root.getChildren().get(0);
        assertEquals("API", api.getValue().name);
        assertEquals(List.of("Health", "Admin"),
                api.getChildren().stream().map(i -> i.getValue().name).toList());
    }

    @Test
    void reloadPicksUpModelChangesAndKeepsExpansion() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        RestCollectionStore store = seeded();
        String apiId = store.collections().get(0).id;
        TreeItem<CollectionNode> root = onFx(() -> {
            CollectionsPanel panel = new CollectionsPanel(store, () -> "{}", j -> {}, s -> {});
            TreeView<CollectionNode> tree = treeOf(panel);
            tree.getRoot().getChildren().get(0).setExpanded(true);
            RestCollectionTree.add(store.collections(), apiId, CollectionNode.request("Metrics",
                    JSON.createObjectNode().put("method", "GET").put("url", "https://api/metrics")));
            panel.reload();
            return tree.getRoot();
        });
        TreeItem<CollectionNode> api = root.getChildren().get(0);
        assertTrue(api.isExpanded(), "expansion should survive a reload");
        assertEquals(3, api.getChildren().size());
    }

    @Test
    void defaultRequestNameIsMethodPlusPath() {
        assertEquals("GET /v1/users", CollectionsPanel.defaultRequestName(
                "{\"method\":\"GET\",\"url\":\"https://api.example.com/v1/users?page=2\"}"));
        assertEquals("POST /", CollectionsPanel.defaultRequestName(
                "{\"method\":\"POST\",\"url\":\"https://api.example.com\"}"));
        // A relative or templated URL has no scheme to strip — keep it whole rather than guessing.
        assertEquals("GET ${BASE_URL}/users", CollectionsPanel.defaultRequestName(
                "{\"method\":\"GET\",\"url\":\"${BASE_URL}/users\"}"));
        assertEquals("New request", CollectionsPanel.defaultRequestName("not json"));
    }

    @Test
    void importAcceptsAWrapperObjectOrABareArray() throws IOException {
        String wrapper = "{\"collections\":[{\"name\":\"A\",\"folder\":true,\"children\":[]}]}";
        String bare = "[{\"name\":\"B\",\"folder\":true,\"children\":[]}]";
        assertEquals("A", CollectionsPanel.readCollections(wrapper).get(0).name);
        assertEquals("B", CollectionsPanel.readCollections(bare).get(0).name);
        assertTrue(CollectionsPanel.readCollections("{}").isEmpty());
    }
}
