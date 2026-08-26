package com.nexuslink.protocol.http.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Runs a list of requests in order — Postman's collection runner — with iterations, an optional data
 * file, assertions per request, and value extraction that feeds the next request.
 *
 * <p>The pieces already existed separately: {@link ResponseAssertions} decides whether a response is
 * acceptable, {@link ResponseExtraction} lifts a value out of it, and {@link RestExecutionService}
 * sends the request. What was missing is the driver around them, which is this class — and the whole
 * of it is the loop below, deliberately kept free of UI so it can be tested end to end against a
 * stub sender.
 *
 * <p>Variables are threaded through the run: a value extracted from step 1 is visible to step 2's
 * URL, headers and body, which is what makes "log in, then call the API with the token" work
 * unattended. The data file's row for the current iteration is layered underneath them.
 */
public final class CollectionRunner {

    /** Sends one request and returns its response — {@link RestExecutionService#execute} in practice. */
    @FunctionalInterface
    public interface Sender {
        RestResponse send(RestRequest request);
    }

    /** Supplies the request for an id, and how to describe it in the report. */
    @FunctionalInterface
    public interface RequestSource {
        /**
         * The request for {@code id} with {@code variables} already substituted, or {@code null} when
         * the id no longer resolves (a request deleted between planning and running).
         */
        RestRequest requestFor(String id, Map<String, String> variables);
    }

    private final Sender sender;
    private final RequestSource requests;
    private final Function<String, String> nameOf;
    private volatile boolean cancelled;

    public CollectionRunner(Sender sender, RequestSource requests, Function<String, String> nameOf) {
        this.sender = sender;
        this.requests = requests;
        this.nameOf = nameOf == null ? id -> id : nameOf;
    }

    /** Stops the run after the step in flight — the Stop button. */
    public void cancel() { cancelled = true; }

    /**
     * Runs {@code plan}, reporting each step as it completes.
     *
     * @param seedVariables variables in force before the first request (the environment)
     * @param extractionsOf the extractions configured on a request, by id
     * @param onStep        called after every step, on the calling thread
     */
    public RunReport run(RunPlan plan,
                         Map<String, String> seedVariables,
                         Function<String, List<ResponseExtraction>> extractionsOf,
                         Consumer<RunReport.Step> onStep) {
        RunReport report = new RunReport();
        Map<String, String> variables = new LinkedHashMap<>(
                seedVariables == null ? Map.of() : seedVariables);
        cancelled = false;

        for (RunPlan.Step step : plan.steps()) {
            if (cancelled) { report.markStopped(); break; }

            // The data row is available to the request, but a value extracted earlier in the run wins:
            // a chained token is more specific than a column of the same name.
            Map<String, String> inScope = new LinkedHashMap<>(step.row());
            inScope.putAll(variables);

            RestRequest request = requests.requestFor(step.requestId(), inScope);
            if (request == null) {
                RunReport.Step missing = new RunReport.Step(step.index(), step.iteration(),
                        nameOf.apply(step.requestId()), "", "", 0, 0, false,
                        "request no longer exists", Map.of());
                report.add(missing);
                if (onStep != null) onStep.accept(missing);
                if (plan.stopOnFailure()) { report.markStopped(); break; }
                continue;
            }

            long start = System.nanoTime();
            RestResponse response = sender.send(request);
            long elapsed = (System.nanoTime() - start) / 1_000_000;

            boolean passed = response != null && !response.failed();
            StringBuilder detail = new StringBuilder();
            if (response == null || response.failed()) {
                detail.append(response == null ? "no response" : response.errorMessage());
            } else {
                ResponseAssertions.Report assertions =
                        AssertionSpec.toAssertions(request.getAssertions()).evaluate(response);
                if (!assertions.results().isEmpty()) {
                    detail.append(assertions.summary());
                    if (!assertions.allPassed()) {
                        passed = false;
                        assertions.results().stream().filter(r -> !r.passed()).findFirst()
                                .ifPresent(r -> detail.append(" — ").append(r.message()));
                    }
                }
            }

            Map<String, String> extracted = new LinkedHashMap<>();
            if (response != null && !response.failed() && extractionsOf != null) {
                List<ResponseExtraction> rules = extractionsOf.apply(step.requestId());
                if (rules != null) {
                    for (ResponseExtraction rule : rules) {
                        rule.extract(response).ifPresentOrElse(
                                value -> extracted.put(rule.variable(), value),
                                () -> appendMissing(detail, rule));
                    }
                }
            }
            variables.putAll(extracted);

            RunReport.Step done = new RunReport.Step(step.index(), step.iteration(),
                    nameOf.apply(step.requestId()), request.getMethod(), request.getUrl(),
                    response == null ? 0 : response.statusCode(), elapsed, passed,
                    detail.toString(), extracted);
            report.add(done);
            if (onStep != null) onStep.accept(done);

            if (!passed && plan.stopOnFailure()) { report.markStopped(); break; }
            if (plan.delayMs() > 0) {
                try {
                    Thread.sleep(plan.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    report.markStopped();
                    break;
                }
            }
        }
        return report;
    }

    /**
     * Notes an extraction that produced nothing. This does not fail the step — the response may
     * legitimately not carry the field — but a later step failing for a missing variable is much
     * easier to understand when the report says where it should have come from.
     */
    private static void appendMissing(StringBuilder detail, ResponseExtraction rule) {
        if (detail.length() > 0) detail.append("  ·  ");
        detail.append("no value for ").append(rule.describe());
    }

    /**
     * Substitutes {@code ${name}} in a text using {@code variables}, leaving unknown names in place
     * so a missing variable is visible in the report rather than silently becoming an empty string.
     */
    public static UnaryOperator<String> substitution(Map<String, String> variables) {
        return text -> {
            if (text == null || text.indexOf("${") < 0) return text;
            String out = text;
            for (Map.Entry<String, String> e : variables.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            return out;
        };
    }
}
