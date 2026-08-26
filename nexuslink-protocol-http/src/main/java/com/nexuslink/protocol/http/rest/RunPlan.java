package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a collection run consists of, worked out before anything is sent: which requests, in which
 * order, how many times, and with which row of data each time.
 *
 * <p>Separating the plan from the running is what makes a runner testable at all — the interesting
 * decisions (does iteration 2 use row 2 or row 1 again? what happens when the data file has fewer
 * rows than iterations? does a failure stop the run?) are all decided here, without a server.
 */
public record RunPlan(List<String> requestIds, int iterations, List<Map<String, String>> data,
                      boolean stopOnFailure, long delayMs) {

    public RunPlan {
        requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        iterations = Math.max(1, iterations);
        data = data == null ? List.of() : List.copyOf(data);
        delayMs = Math.max(0, delayMs);
    }

    /** A plain run: every request once, no data file. */
    public static RunPlan of(List<String> requestIds) {
        return new RunPlan(requestIds, 1, List.of(), false, 0);
    }

    /**
     * One step of the run: which request, which iteration it belongs to, and the data row in force.
     * The row is empty when there is no data file.
     */
    public record Step(String requestId, int iteration, int index, Map<String, String> row) {}

    /**
     * The steps in execution order. When a data file is supplied, <b>the number of iterations is the
     * number of rows</b> — running 3 iterations against a 5-row file silently ignoring two rows is
     * never what anyone means; the row count wins and {@link #effectiveIterations()} says so.
     */
    public List<Step> steps() {
        int rounds = effectiveIterations();
        List<Step> steps = new ArrayList<>(rounds * requestIds.size());
        int index = 0;
        for (int iteration = 0; iteration < rounds; iteration++) {
            Map<String, String> row = data.isEmpty() ? Map.of() : data.get(iteration % data.size());
            for (String id : requestIds) {
                steps.add(new Step(id, iteration + 1, index++, row));
            }
        }
        return steps;
    }

    /** How many times the request list actually runs: the row count when there is a data file. */
    public int effectiveIterations() {
        return data.isEmpty() ? iterations : data.size();
    }

    /** How many requests the run will send in total. */
    public int totalSteps() {
        return effectiveIterations() * requestIds.size();
    }

    /**
     * Parses a data file — a JSON array of objects, or CSV with a header row — into the rows that
     * drive the iterations. Every value is a string, because that is what {@code ${var}} substitution
     * puts into a URL or body.
     */
    public static List<Map<String, String>> parseData(String text) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (text == null || text.isBlank()) return rows;
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            try {
                com.fasterxml.jackson.databind.JsonNode array =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                for (com.fasterxml.jackson.databind.JsonNode element : array) {
                    Map<String, String> row = new LinkedHashMap<>();
                    element.fields().forEachRemaining(field -> row.put(field.getKey(),
                            field.getValue().isValueNode() ? field.getValue().asText()
                                    : field.getValue().toString()));
                    rows.add(row);
                }
                return rows;
            } catch (Exception e) {
                throw new IllegalArgumentException("The data file is not a JSON array: " + e.getMessage());
            }
        }
        List<List<String>> csv = parseCsv(trimmed);
        if (csv.size() < 2) return rows;   // a header alone drives nothing
        List<String> header = csv.get(0);
        for (List<String> line : csv.subList(1, csv.size())) {
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i).trim(), i < line.size() ? line.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /** A minimal RFC 4180 reader — quoted fields, doubled quotes, CRLF or LF. */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> { row.add(field.toString()); field.setLength(0); }
                case '\r' -> { }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
        return rows;
    }
}
