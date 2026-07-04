package com.nexuslink.core.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs an ordered list of connection-diagnostic probes (DNS → TCP → TLS → SASL → Admin, or whatever a
 * protocol needs) sequentially, stopping at the first failure and marking the rest skipped. Each probe
 * returns a short detail string on success or throws on failure (its message becomes the detail). The
 * runner is pure and JavaFX-free — the probes carry the actual network I/O — so the sequencing, timing
 * and stop-on-failure logic is fully unit-testable with synthetic probes. A per-step callback lets a
 * wizard update its UI as each step completes.
 */
public final class ConnectionDiagnostics {

    /** Outcome of a single step. */
    public enum Status { PASSED, FAILED, SKIPPED }

    /** The result of one diagnostic step: its name, outcome, a detail line, and how long it took. */
    public record StepResult(String name, Status status, String detail, long elapsedMs) {
        public boolean ok() { return status == Status.PASSED; }
    }

    /** A single probe: returns a detail string on success, or throws to signal failure. */
    @FunctionalInterface
    public interface Probe {
        String probe() throws Exception;
    }

    /** A named diagnostic step. */
    public record Step(String name, Probe probe) {}

    private ConnectionDiagnostics() {}

    /** Runs the steps with no per-step callback. */
    public static List<StepResult> run(List<Step> steps) {
        return run(steps, r -> {});
    }

    /**
     * Runs {@code steps} in order. Each is probed until one fails; that step is {@code FAILED} and every
     * later step is {@code SKIPPED} without being probed. {@code onStep} is invoked once per step (in
     * order, including skipped ones) so a wizard can render progress live. Returns all results in order.
     */
    public static List<StepResult> run(List<Step> steps, Consumer<StepResult> onStep) {
        List<StepResult> results = new ArrayList<>();
        Consumer<StepResult> notify = onStep == null ? r -> {} : onStep;
        boolean aborted = false;
        for (Step step : steps) {
            StepResult result;
            if (aborted) {
                result = new StepResult(step.name(), Status.SKIPPED, "skipped — a previous step failed", 0);
            } else {
                long start = System.nanoTime();
                try {
                    String detail = step.probe().probe();
                    result = new StepResult(step.name(), Status.PASSED, detail == null ? "" : detail, elapsed(start));
                } catch (Exception e) {
                    result = new StepResult(step.name(), Status.FAILED,
                            e.getMessage() == null ? e.toString() : e.getMessage(), elapsed(start));
                    aborted = true;
                }
            }
            results.add(result);
            notify.accept(result);
        }
        return results;
    }

    /** True when every step passed (an empty run counts as passed). */
    public static boolean allPassed(List<StepResult> results) {
        return results.stream().allMatch(StepResult::ok);
    }

    private static long elapsed(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0);
    }
}
