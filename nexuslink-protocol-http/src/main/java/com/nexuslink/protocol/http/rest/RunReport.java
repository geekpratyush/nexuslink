package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of a collection run: one row per request sent, plus the totals that decide whether the
 * run passed.
 *
 * <p>A run is only useful if its verdict is unambiguous, so a step counts as passed when the request
 * completed <em>and</em> every assertion on it held — a 500 with no assertions is still a failure
 * worth seeing, and an assertion failure on a 200 is a failure too.
 */
public final class RunReport {

    /** One request's outcome inside the run. */
    public record Step(int index, int iteration, String requestName, String method, String url,
                       int statusCode, long durationMs, boolean passed, String detail,
                       Map<String, String> extracted) {

        /** A one-line rendering for the report table. */
        public String summary() {
            return (passed ? "PASS  " : "FAIL  ") + method + " " + url
                    + "  →  " + (statusCode > 0 ? statusCode : "no response")
                    + "  ·  " + durationMs + " ms"
                    + (detail == null || detail.isBlank() ? "" : "  ·  " + detail);
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private final Map<String, String> variables = new LinkedHashMap<>();
    private long totalMs;
    private boolean stopped;

    /** Records one step's outcome. */
    public void add(Step step) {
        steps.add(step);
        if (step.extracted() != null) variables.putAll(step.extracted());
        totalMs += step.durationMs();
    }

    /** Marks the run as having stopped early (stop-on-failure, or cancelled). */
    public void markStopped() { stopped = true; }

    public List<Step> steps() { return List.copyOf(steps); }

    /** Every variable extracted during the run, latest value per name. */
    public Map<String, String> variables() { return Map.copyOf(variables); }

    public int passed() { return (int) steps.stream().filter(Step::passed).count(); }

    public int failed() { return steps.size() - passed(); }

    public long totalMs() { return totalMs; }

    /** {@code true} when the run stopped before every planned step ran. */
    public boolean stoppedEarly() { return stopped; }

    /** {@code true} when every step that ran passed and nothing stopped the run. */
    public boolean isPass() { return failed() == 0 && !stopped; }

    /** The headline line, e.g. {@code 11 passed · 1 failed · 2.4 s}. */
    public String summary() {
        StringBuilder sb = new StringBuilder(passed() + " passed");
        if (failed() > 0) sb.append(" · ").append(failed()).append(" failed");
        sb.append(" · ").append(String.format("%.1f", totalMs / 1000.0)).append(" s");
        if (stopped) sb.append(" · stopped early");
        return sb.toString();
    }
}
