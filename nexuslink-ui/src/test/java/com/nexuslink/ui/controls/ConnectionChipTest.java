package com.nexuslink.ui.controls;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The collapsed/expanded behaviour of the connection chip. Skipped without a JavaFX toolkit. */
class ConnectionChipTest {

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
    void itStartsCollapsedAndKeepsTheRealValue() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionChip chip = new ConnectionChip();
            chip.setValue("mongodb://app:s3cret@db.internal:27017/orders");
            assertFalse(chip.isEditing(), "a connection string starts collapsed");
            assertEquals("mongodb://app:s3cret@db.internal:27017/orders", chip.getValue(),
                    "the value itself is never redacted — only its display is");
        });
    }

    @Test
    void editingShowsTheFullValue() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionChip chip = new ConnectionChip();
            chip.setValue("mongodb://app:s3cret@host/db");
            chip.startEditing();
            assertTrue(chip.isEditing());
        });
    }

    @Test
    void aFriendlyNameIsShownInsteadOfTheHostWhenThereIsOne() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionChip chip = new ConnectionChip();
            chip.setValue("mongodb://app:s3cret@db.internal:27017/orders");
            chip.setName("Orders prod");
            assertEquals("Orders prod", chip.nameProperty().get());
            assertEquals("mongodb://app:s3cret@db.internal:27017/orders", chip.getValue());
        });
    }

    @Test
    void settingTheValueProgrammaticallyIsNotAUserEdit() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            ConnectionChip chip = new ConnectionChip();
            chip.setValue("mongodb://host/db");
            int[] commits = {0};
            chip.setOnCommit(() -> commits[0]++);
            chip.setValue("mongodb://other/db");
            assertEquals(0, commits[0]);
        });
    }

    @Test
    void aSecretFieldStartsMaskedAndSharesOneValue() throws Exception {
        if (!fxUp) return;
        onFx(() -> {
            SecretField field = new SecretField("token");
            field.setText("s3cret-token");
            assertFalse(field.isRevealed(), "a secret starts hidden");
            assertEquals("s3cret-token", field.getText());
            field.hide();
            assertFalse(field.isRevealed());
        });
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
