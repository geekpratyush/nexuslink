package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tiny, offline assertion model evaluated against a {@link RestResponse}.
 * <p>
 * Each {@link Assertion} is a self-contained check (status code, header, body
 * text, or JSON path) that can be authored in the UI and replayed without a
 * live server. Evaluation never throws — a malformed expression simply yields a
 * failing {@link Result} with an explanatory message — so it is safe to run a
 * whole suite and report the outcomes.
 */
public final class ResponseAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Assertion> assertions = new ArrayList<>();

    /** The kind of check an {@link Assertion} performs. */
    public enum Type {
        /** Status code equals an exact value (the {@code target} of the assertion). */
        STATUS_EQUALS,
        /** Status code falls within an inclusive {@code [min, max]} range. */
        STATUS_IN_RANGE,
        /** A named response header equals a value (case-insensitive header name). */
        HEADER_EQUALS,
        /** A named response header contains a substring. */
        HEADER_CONTAINS,
        /** The response body contains a substring. */
        BODY_CONTAINS,
        /** A JSON-pointer path into the body equals an expected (string) value. */
        JSON_PATH_EQUALS
    }

    /** Adds a pre-built assertion and returns {@code this} for chaining. */
    public ResponseAssertions add(Assertion assertion) {
        assertions.add(assertion);
        return this;
    }

    public ResponseAssertions statusEquals(int code) {
        return add(Assertion.statusEquals(code));
    }

    public ResponseAssertions statusInRange(int minInclusive, int maxInclusive) {
        return add(Assertion.statusInRange(minInclusive, maxInclusive));
    }

    public ResponseAssertions headerEquals(String name, String value) {
        return add(Assertion.headerEquals(name, value));
    }

    public ResponseAssertions headerContains(String name, String substring) {
        return add(Assertion.headerContains(name, substring));
    }

    public ResponseAssertions bodyContains(String substring) {
        return add(Assertion.bodyContains(substring));
    }

    public ResponseAssertions jsonPathEquals(String pointer, String expected) {
        return add(Assertion.jsonPathEquals(pointer, expected));
    }

    /** The assertions registered so far, in insertion order. */
    public List<Assertion> assertions() {
        return List.copyOf(assertions);
    }

    /** Evaluates every assertion against {@code response} and returns the report. */
    public Report evaluate(RestResponse response) {
        List<Result> results = new ArrayList<>(assertions.size());
        for (Assertion a : assertions) {
            results.add(evaluateOne(a, response));
        }
        return new Report(results);
    }

    private static Result evaluateOne(Assertion a, RestResponse response) {
        try {
            return switch (a.type) {
                case STATUS_EQUALS -> {
                    int expected = a.intTarget;
                    int actual = response.statusCode();
                    yield Result.of(a, actual == expected,
                            "status " + actual + (actual == expected ? " == " : " != ") + expected);
                }
                case STATUS_IN_RANGE -> {
                    int actual = response.statusCode();
                    boolean ok = actual >= a.intTarget && actual <= a.intMax;
                    yield Result.of(a, ok, "status " + actual
                            + (ok ? " within " : " outside ") + "[" + a.intTarget + ".." + a.intMax + "]");
                }
                case HEADER_EQUALS -> {
                    String actual = firstHeader(response, a.name);
                    boolean ok = actual != null && actual.equals(a.target);
                    yield Result.of(a, ok, describeHeader(a.name, actual)
                            + (ok ? " == " : " != ") + quote(a.target));
                }
                case HEADER_CONTAINS -> {
                    String actual = firstHeader(response, a.name);
                    boolean ok = actual != null && actual.contains(a.target);
                    yield Result.of(a, ok, describeHeader(a.name, actual)
                            + (ok ? " contains " : " missing ") + quote(a.target));
                }
                case BODY_CONTAINS -> {
                    String body = response.body() == null ? "" : response.body();
                    boolean ok = body.contains(a.target);
                    yield Result.of(a, ok, "body "
                            + (ok ? "contains " : "missing ") + quote(a.target));
                }
                case JSON_PATH_EQUALS -> evaluateJsonPath(a, response);
            };
        } catch (RuntimeException e) {
            return Result.of(a, false, "error: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static Result evaluateJsonPath(Assertion a, RestResponse response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return Result.of(a, false, "JSON path " + quote(a.name) + ": empty body");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return Result.of(a, false, "JSON path " + quote(a.name) + ": invalid JSON");
        }
        JsonNode node = root.at(toPointer(a.name));
        if (node.isMissingNode()) {
            return Result.of(a, false, "JSON path " + quote(a.name) + " not found");
        }
        String actual = node.isValueNode() ? node.asText() : node.toString();
        boolean ok = actual.equals(a.target);
        return Result.of(a, ok, "JSON path " + quote(a.name) + " = " + quote(actual)
                + (ok ? " == " : " != ") + quote(a.target));
    }

    /**
     * Accepts either a JSON-pointer ({@code /a/b/0}) or a dotted path
     * ({@code a.b.0}) and returns a normalised JSON-pointer string.
     */
    private static String toPointer(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("/")) return path;
        String dotted = path.startsWith("$.") ? path.substring(2)
                : path.startsWith("$") ? path.substring(1) : path;
        StringBuilder sb = new StringBuilder();
        for (String seg : dotted.split("\\.")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(seg.replace("~", "~0").replace("/", "~1"));
        }
        return sb.toString();
    }

    private static String firstHeader(RestResponse response, String name) {
        Map<String, List<String>> headers = response.headers();
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> values = e.getValue();
                return (values == null || values.isEmpty()) ? null : values.get(0);
            }
        }
        return null;
    }

    private static String describeHeader(String name, String actual) {
        return "header " + name + "=" + (actual == null ? "(absent)" : quote(actual));
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    /** A single declarative check. Build via the static factories. */
    public static final class Assertion {
        private final Type type;
        private final String name;    // header name or JSON path; null otherwise
        private final String target;  // expected string value; null for status checks
        private final int intTarget;  // status code / range lower bound
        private final int intMax;     // range upper bound

        private Assertion(Type type, String name, String target, int intTarget, int intMax) {
            this.type = type;
            this.name = name;
            this.target = target;
            this.intTarget = intTarget;
            this.intMax = intMax;
        }

        public static Assertion statusEquals(int code) {
            return new Assertion(Type.STATUS_EQUALS, null, null, code, code);
        }

        public static Assertion statusInRange(int minInclusive, int maxInclusive) {
            return new Assertion(Type.STATUS_IN_RANGE, null, null, minInclusive, maxInclusive);
        }

        public static Assertion headerEquals(String name, String value) {
            return new Assertion(Type.HEADER_EQUALS, name, value, 0, 0);
        }

        public static Assertion headerContains(String name, String substring) {
            return new Assertion(Type.HEADER_CONTAINS, name, substring, 0, 0);
        }

        public static Assertion bodyContains(String substring) {
            return new Assertion(Type.BODY_CONTAINS, null, substring, 0, 0);
        }

        public static Assertion jsonPathEquals(String pointer, String expected) {
            return new Assertion(Type.JSON_PATH_EQUALS, pointer, expected, 0, 0);
        }

        public Type type() { return type; }
        public String name() { return name; }
        public String target() { return target; }

        /** A short human-readable label for display in a results table. */
        public String label() {
            return switch (type) {
                case STATUS_EQUALS -> "status == " + intTarget;
                case STATUS_IN_RANGE -> "status in [" + intTarget + ".." + intMax + "]";
                case HEADER_EQUALS -> "header " + name + " == " + quote(target);
                case HEADER_CONTAINS -> "header " + name + " contains " + quote(target);
                case BODY_CONTAINS -> "body contains " + quote(target);
                case JSON_PATH_EQUALS -> "json " + name + " == " + quote(target);
            };
        }
    }

    /** The outcome of evaluating a single {@link Assertion}. */
    public record Result(Assertion assertion, boolean passed, String message) {
        static Result of(Assertion assertion, boolean passed, String message) {
            return new Result(assertion, passed, message);
        }
    }

    /** The aggregate outcome of evaluating a whole set of assertions. */
    public record Report(List<Result> results) {
        public Report {
            results = List.copyOf(results);
        }

        public boolean allPassed() {
            return results.stream().allMatch(Result::passed);
        }

        public int passedCount() {
            return (int) results.stream().filter(Result::passed).count();
        }

        public int failedCount() {
            return results.size() - passedCount();
        }

        /** A compact one-line summary, e.g. {@code "3/4 passed"}. */
        public String summary() {
            return passedCount() + "/" + results.size() + " passed";
        }
    }
}
