package com.nexuslink.ui.rest;

import com.nexuslink.plugin.codegen.CodeGenRegistry;
import com.nexuslink.plugin.codegen.CodeGenTarget;
import com.nexuslink.plugin.codegen.CodeGenerator;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The code-gen window is protocol-agnostic: it renders whatever the registry says applies to the
 * request. Runs on a real JavaFX thread; skipped when no toolkit is available.
 */
class CodeGenDialogTest {

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

    /** A generator claiming Strings, rendering "<protocol>/<target>: <request>". */
    private record FakeGenerator(String id) implements CodeGenerator {
        @Override public String protocolId() { return id; }
        @Override public String displayName() { return id.toUpperCase(); }
        @Override public boolean supports(Object request) { return request instanceof String; }
        @Override public List<CodeGenTarget> targets() {
            return List.of(CodeGenTarget.of("cli", "CLI"), CodeGenTarget.of("java", "Java"));
        }
        @Override public String generate(CodeGenTarget target, Object request) {
            return id + "/" + target.id() + ": " + request;
        }
    }

    @Test
    void rendersTheFirstTargetAndRegeneratesOnSwitch() throws Exception {
        if (!fxUp) return;
        CodeGenRegistry registry = CodeGenRegistry.fromProviders(List.of(new FakeGenerator("rest")));
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        onFx(() -> {
            try {
                CodeGenDialog.show(null, "my-request", registry);
                Stage stage = openStage();
                TextArea code = (TextArea) stage.getScene().lookup("#codegen-output");
                assertNotNull(code);
                assertEquals("rest/cli: my-request", code.getText(), "the first target renders on open");

                @SuppressWarnings("unchecked")
                ComboBox<CodeGenTarget> targets = (ComboBox<CodeGenTarget>) stage.getScene().getRoot()
                        .lookupAll(".combo-box").stream().findFirst().orElseThrow();
                targets.setValue(targets.getItems().get(1));
                assertEquals("rest/java: my-request", code.getText(), "switching target regenerates");
                stage.close();
            } catch (AssertionError e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    @Test
    void showsAProtocolChooserOnlyWhenSeveralGeneratorsApply() throws Exception {
        if (!fxUp) return;
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        onFx(() -> {
            try {
                CodeGenRegistry one = CodeGenRegistry.fromProviders(List.of(new FakeGenerator("rest")));
                CodeGenDialog.show(null, "req", one);
                Stage single = openStage();
                assertEquals(1, single.getScene().getRoot().lookupAll(".combo-box").size(),
                        "one generator → language dropdown only");
                single.close();

                CodeGenRegistry two = CodeGenRegistry.fromProviders(
                        List.of(new FakeGenerator("rest"), new FakeGenerator("kafka")));
                CodeGenDialog.show(null, "req", two);
                Stage both = openStage();
                assertEquals(2, both.getScene().getRoot().lookupAll(".combo-box").size(),
                        "two generators → protocol dropdown appears");
                both.close();
            } catch (AssertionError e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    @Test
    void opensNothingWhenNoGeneratorClaimsTheRequest() throws Exception {
        if (!fxUp) return;
        CodeGenRegistry registry = CodeGenRegistry.fromProviders(List.of(new FakeGenerator("rest")));
        AtomicReference<AssertionError> failure = new AtomicReference<>();

        onFx(() -> {
            try {
                int before = Window.getWindows().size();
                // An Integer request is unsupported: the dialog must not open a code window.
                // (The information Alert is modal, so this asserts on the non-alert path only.)
                assertTrue(registry.generatorsFor(42).isEmpty());
                assertEquals(before, Window.getWindows().size());
            } catch (AssertionError e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
    }

    /** The most recently shown window, which is the dialog under test. */
    private static Stage openStage() {
        return (Stage) Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof Stage)
                .reduce((first, second) -> second)
                .orElseThrow();
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
