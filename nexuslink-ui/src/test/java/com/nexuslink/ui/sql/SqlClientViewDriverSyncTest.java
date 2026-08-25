package com.nexuslink.ui.sql;

import com.nexuslink.protocol.db.DriverInfo;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opening a tab on a saved connection must show the database that connection actually uses — the
 * picker used to keep its SQLite default no matter what URL was prefilled.
 */
class SqlClientViewDriverSyncTest {

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
        // Closing the last window would otherwise shut the toolkit down and strand the next test.
        if (fxUp) Platform.runLater(() -> Platform.setImplicitExit(false));
    }

    private <T> T onFx(Callable<T> work) throws Exception {
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
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FX work did not run");
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private String[] prefillAndRead(String url) throws Exception {
        return onFx(() -> {
            SqlClientView view = new SqlClientView();
            view.prefill(url, "app", "secret");
            ComboBox<DriverInfo> combo = (ComboBox<DriverInfo>) view.lookup("#sqlDbCombo");
            TextField urlField = (TextField) view.lookup("#sqlUrl");
            return new String[]{combo.getValue() == null ? null : combo.getValue().id(),
                    urlField.getText()};
        });
    }

    @Test
    void prefillSelectsTheDriverTheUrlNames() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        assertEquals("mysql", prefillAndRead("jdbc:mysql://db.corp:3306/app")[0]);
        assertEquals("oracle", prefillAndRead("jdbc:oracle:thin:@//db.corp:1521/ORCLPDB1")[0]);
        assertEquals("postgresql", prefillAndRead("jdbc:postgresql://db.corp:5432/app")[0]);
    }

    @Test
    void followingTheUrlDoesNotOverwriteItWithTheTemplate() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        String url = "jdbc:mysql://db.corp:3306/app";
        assertEquals(url, prefillAndRead(url)[1]);
    }

    @Test
    void aStoredDriverIdWinsOverWhatTheUrlImplies() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        // CockroachDB speaks the PostgreSQL wire protocol, so only the saved id can tell them apart.
        String id = onFx(() -> {
            SqlClientView view = new SqlClientView();
            view.prefill("jdbc:postgresql://roach:26257/defaultdb", "app", "", "cockroachdb");
            @SuppressWarnings("unchecked")
            ComboBox<DriverInfo> combo = (ComboBox<DriverInfo>) view.lookup("#sqlDbCombo");
            return combo.getValue().id();
        });
        assertEquals("cockroachdb", id);
    }

    @Test
    void connectNowSkipsADriverThatIsNotInstalled() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        assertEquals(Boolean.FALSE, onFx(() -> {
            SqlClientView view = new SqlClientView();
            view.prefill("jdbc:oracle:thin:@//db.corp:1521/ORCLPDB1", "app", "secret");
            return view.connectNow();
        }));
        // …but a bundled driver connects straight away, which is the point of double-click-to-open.
        assertEquals(Boolean.TRUE, onFx(() -> {
            SqlClientView view = new SqlClientView();
            view.prefill("jdbc:sqlite::memory:", null, null);
            return view.connectNow();
        }));
    }

    @Test
    void anUnknownUrlLeavesThePickerAlone() throws Exception {
        assumeTrue(fxUp, "JavaFX toolkit unavailable");
        String[] state = prefillAndRead("jdbc:informix-sqli://host/db");
        assertEquals("sqlite", state[0]);
        assertEquals("jdbc:informix-sqli://host/db", state[1]);
    }
}
