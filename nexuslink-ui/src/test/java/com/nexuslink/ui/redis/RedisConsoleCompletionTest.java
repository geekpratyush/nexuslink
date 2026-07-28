package com.nexuslink.ui.redis;

import com.nexuslink.protocol.redis.RedisCommandCatalog;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The console's command-name completion popup. Skipped when no JavaFX toolkit is available. */
class RedisConsoleCompletionTest {

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
    void offersMatchesForTheFirstTokenOnly() throws Exception {
        if (!fxUp) return;
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        onFx(() -> {
            try {
                RedisView view = new RedisView();
                new Scene(view);
                view.applyCss();
                view.layout();

                TextField command = view.consoleCommandField();
                ContextMenu popup = view.consoleCompletionPopup();

                command.setText("SUB");
                assertFalse(popup.getItems().isEmpty(), "a prefix should offer completions");
                assertTrue(popup.getItems().get(0).getText().startsWith("SUBSCRIBE"),
                        popup.getItems().get(0).getText());

                // Picking an item replaces the token and leaves a trailing space for the arguments.
                popup.getItems().get(0).fire();
                assertEquals("SUBSCRIBE ", command.getText());
                assertFalse(popup.isShowing(), "picking a completion dismisses the popup");

                // Once arguments are being typed there is nothing to complete.
                command.setText("GET my");
                assertFalse(popup.isShowing());

                command.setText("zzz-not-a-command");
                assertFalse(popup.isShowing(), "an unmatched prefix shows nothing");
            } catch (AssertionError e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    @Test
    void completionsComeFromTheCatalog() {
        List<RedisCommandCatalog.Command> matches = RedisCommandCatalog.complete("sub");

        assertFalse(matches.isEmpty());
        assertTrue(matches.stream().allMatch(c -> c.name().toUpperCase().startsWith("SUB")),
                "the catalog completes case-insensitively on the command name");
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
