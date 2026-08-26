package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pulls a value out of a response and names it, so the next request can use it — the rule that turns
 * a list of requests into a chain.
 *
 * <p>Without this, using a login's token in the next call means copying it by hand every time, which
 * is why request chaining is the feature people ask for first. An extraction says <em>where</em> the
 * value is (a JSON path, a header, a regex capture over the body, or the status code) and
 * <em>what to call it</em>; the caller stores the result as an environment variable that
 * {@code ${name}} then resolves.
 *
 * <p>Pure: it reads a {@link RestResponse}, so every source and every failure mode is unit-testable
 * without a server.
 */
public record ResponseExtraction(String variable, Source source, String expression) {

    /** Where the value comes from. */
    public enum Source {
        /** A JSON pointer ({@code /data/token}) or dotted path ({@code data.token}) into the body. */
        JSON_PATH,
        /** A response header, matched case-insensitively. */
        HEADER,
        /** The first capture group of a regular expression over the body text. */
        REGEX,
        /** The HTTP status code. */
        STATUS,
        /** The whole body as text. */
        BODY
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ResponseExtraction {
        variable = variable == null ? "" : variable.trim();
        expression = expression == null ? "" : expression.trim();
    }

    /** {@code true} when this extraction names a variable and has what its source needs. */
    public boolean isComplete() {
        if (variable.isEmpty()) return false;
        return switch (source) {
            case STATUS, BODY -> true;
            case JSON_PATH, HEADER, REGEX -> !expression.isEmpty();
        };
    }

    /**
     * The extracted value, or empty when the response does not contain it — a missing value is a
     * normal outcome (the login failed, the field was absent) and is reported rather than thrown, so
     * a run can carry on and say which step did not produce its variable.
     */
    public Optional<String> extract(RestResponse response) {
        if (response == null || !isComplete()) return Optional.empty();
        return switch (source) {
            case STATUS -> Optional.of(String.valueOf(response.statusCode()));
            case BODY -> Optional.ofNullable(response.body());
            case HEADER -> Optional.ofNullable(header(response, expression));
            case JSON_PATH -> jsonValue(response.body(), expression);
            case REGEX -> regexValue(response.body(), expression);
        };
    }

    /** A description for the results panel, e.g. {@code token ← JSON /data/token}. */
    public String describe() {
        return variable + " ← " + switch (source) {
            case JSON_PATH -> "JSON " + expression;
            case HEADER -> "header " + expression;
            case REGEX -> "regex " + expression;
            case STATUS -> "status code";
            case BODY -> "whole body";
        };
    }

    private static String header(RestResponse response, String name) {
        Map<String, List<String>> headers = response.headers();
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> values = e.getValue();
                return values == null || values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    /** Reads a JSON pointer or dotted path out of the body. */
    private static Optional<String> jsonValue(String body, String path) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode node = root.at(toPointer(path));
            if (node.isMissingNode() || node.isNull()) return Optional.empty();
            return Optional.of(node.isValueNode() ? node.asText() : node.toString());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The first capture group (or the whole match when the pattern has no group). */
    private static Optional<String> regexValue(String body, String pattern) {
        if (body == null) return Optional.empty();
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(body);
            if (!matcher.find()) return Optional.empty();
            return Optional.of(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
        } catch (java.util.regex.PatternSyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Accepts a JSON pointer ({@code /a/b/0}), a dotted path ({@code a.b.0}) or a {@code $.}-prefixed
     * one, and normalises to a pointer — the same spelling {@link ResponseAssertions} accepts, so a
     * path that works in an assertion works here.
     */
    static String toPointer(String path) {
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

    /** Parses {@code name = source:expression} lines — the compact form the editor stores. */
    public static List<ResponseExtraction> parse(String text) {
        List<ResponseExtraction> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int equals = trimmed.indexOf('=');
            if (equals <= 0) continue;
            String variable = trimmed.substring(0, equals).trim();
            String rest = trimmed.substring(equals + 1).trim();
            int colon = rest.indexOf(':');
            String sourceName = colon < 0 ? rest : rest.substring(0, colon);
            String expression = colon < 0 ? "" : rest.substring(colon + 1).trim();
            Source source;
            try {
                source = Source.valueOf(sourceName.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                source = Source.JSON_PATH;      // an unqualified path is the common case
                expression = rest;
            }
            out.add(new ResponseExtraction(variable, source, expression));
        }
        return out;
    }

    /** Renders extractions back to the compact form. */
    public static String render(List<ResponseExtraction> extractions) {
        StringBuilder sb = new StringBuilder();
        for (ResponseExtraction e : extractions) {
            sb.append(e.variable()).append(" = ").append(e.source().name().toLowerCase(Locale.ROOT));
            if (!e.expression().isEmpty()) sb.append(": ").append(e.expression());
            sb.append('\n');
        }
        return sb.toString();
    }
}
