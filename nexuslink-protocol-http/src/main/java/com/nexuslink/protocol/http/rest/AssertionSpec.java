package com.nexuslink.protocol.http.rest;

import java.util.List;

/**
 * A mutable, editor-friendly description of one response assertion.
 * <p>
 * The UI binds a table of these (type + up to three string fields), then
 * {@link #toAssertions(List)} compiles the enabled rows into an immutable
 * {@link ResponseAssertions} for evaluation. Keeping the spec separate from the
 * evaluated {@link ResponseAssertions.Assertion} lets the table hold
 * half-finished rows without the strict typing the evaluator needs.
 */
public final class AssertionSpec {

    private boolean enabled = true;
    private ResponseAssertions.Type type = ResponseAssertions.Type.STATUS_EQUALS;
    /** Header name or JSON path; unused for status/body checks. */
    private String name = "";
    /** Expected value, status code, or range lower bound (interpreted per type). */
    private String target = "";
    /** Range upper bound; only meaningful for {@code STATUS_IN_RANGE}. */
    private String max = "";

    public AssertionSpec() {
    }

    public AssertionSpec(ResponseAssertions.Type type, String name, String target, String max) {
        this.type = type;
        this.name = name == null ? "" : name;
        this.target = target == null ? "" : target;
        this.max = max == null ? "" : max;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ResponseAssertions.Type getType() { return type; }
    public void setType(ResponseAssertions.Type type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target == null ? "" : target; }

    public String getMax() { return max; }
    public void setMax(String max) { this.max = max == null ? "" : max; }

    /** True when this row carries enough to compile into an assertion. */
    public boolean isComplete() {
        return switch (type) {
            case STATUS_EQUALS -> isInt(target);
            case STATUS_IN_RANGE -> isInt(target) && isInt(max);
            case HEADER_EQUALS, HEADER_CONTAINS, JSON_PATH_EQUALS -> !name.isBlank();
            case BODY_CONTAINS -> !target.isBlank();
        };
    }

    /** Compiles this row into an evaluable assertion (caller checks {@link #isComplete()} first). */
    public ResponseAssertions.Assertion toAssertion() {
        return switch (type) {
            case STATUS_EQUALS -> ResponseAssertions.Assertion.statusEquals(parseInt(target));
            case STATUS_IN_RANGE -> ResponseAssertions.Assertion.statusInRange(parseInt(target), parseInt(max));
            case HEADER_EQUALS -> ResponseAssertions.Assertion.headerEquals(name, target);
            case HEADER_CONTAINS -> ResponseAssertions.Assertion.headerContains(name, target);
            case BODY_CONTAINS -> ResponseAssertions.Assertion.bodyContains(target);
            case JSON_PATH_EQUALS -> ResponseAssertions.Assertion.jsonPathEquals(name, target);
        };
    }

    /** Builds a {@link ResponseAssertions} from every enabled, complete spec in {@code specs}. */
    public static ResponseAssertions toAssertions(List<AssertionSpec> specs) {
        ResponseAssertions ra = new ResponseAssertions();
        if (specs == null) return ra;
        for (AssertionSpec spec : specs) {
            if (spec.enabled && spec.isComplete()) {
                ra.add(spec.toAssertion());
            }
        }
        return ra;
    }

    private static boolean isInt(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            Integer.parseInt(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
