package com.nexuslink.protocol.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parsed {@code mongosh}-style line: {@code db.<collection>.<operation>(<args>)} plus any chained
 * modifiers ({@code .sort(...)}, {@code .limit(...)}, {@code .skip(...)}, {@code .count()}).
 *
 * <p>This is deliberately a <b>grammar parser, not a JavaScript engine</b>. Embedding a JS runtime
 * to run arbitrary shell scripts would be a different product; parsing the call shape covers the way
 * the shell is actually used in a GUI — one statement at a time against one collection — and it
 * fails honestly on anything else instead of half-executing it. {@link #unsupportedReason()} says so
 * in as many words when a line is beyond the grammar.
 *
 * <p>Pure: parsing has no connection, so the whole grammar is unit-testable.
 */
public record MongoShellCommand(
        String collection,
        String operation,
        List<String> arguments,
        String sort,
        String projection,
        Integer limit,
        Integer skip,
        boolean count,
        String unsupportedReason
) {

    /** Operations that read and therefore return documents. */
    private static final List<String> READ_OPS = List.of("find", "findone", "aggregate", "distinct");

    /** Database-level helpers, written {@code db.<op>()} with no collection. */
    private static final List<String> DATABASE_OPS = List.of(
            "getcollectionnames", "getcollectioninfos", "stats", "version");

    /** Operations this shell can carry out through the driver. */
    private static final List<String> KNOWN_OPS = List.of(
            "find", "findone", "aggregate", "distinct", "count", "countdocuments",
            "insertone", "insertmany", "updateone", "updatemany", "deleteone", "deletemany",
            "replaceone", "drop", "createindex", "dropindex", "getindexes", "stats", "explain");

    /** A line the grammar cannot represent — carries the reason to show the user. */
    public static MongoShellCommand unsupported(String reason) {
        return new MongoShellCommand(null, null, List.of(), null, null, null, null, false, reason);
    }

    /** {@code true} when this line parsed into something the client can run. */
    public boolean isRunnable() { return unsupportedReason == null; }

    /** {@code true} for a {@code db.<op>()} helper that targets the database rather than a collection. */
    public boolean isDatabaseLevel() { return isRunnable() && collection == null; }

    /** {@code true} when the operation returns documents rather than a count or a write result. */
    public boolean isRead() { return operation != null && READ_OPS.contains(operation); }

    /** The first argument, or {@code {}} when the call had none — the filter for most operations. */
    public String firstArgument() { return arguments.isEmpty() ? "{}" : arguments.get(0); }

    /** The second argument, or {@code null} — the update document, or a find's projection. */
    public String secondArgument() { return arguments.size() < 2 ? null : arguments.get(1); }

    /**
     * Parses one shell line.
     *
     * @return a runnable command, or one carrying {@link #unsupportedReason()}
     */
    public static MongoShellCommand parse(String line) {
        if (line == null || line.isBlank()) return unsupported("Type a command, e.g. db.people.find({})");
        String text = line.trim();
        if (text.endsWith(";")) text = text.substring(0, text.length() - 1).trim();

        if (!text.startsWith("db.")) {
            return unsupported("Only db.<collection>.<operation>(…) lines are supported here — "
                    + "this shell parses the call shape rather than running JavaScript");
        }
        String rest = text.substring(3);

        int firstDot = rest.indexOf('.');
        if (firstDot <= 0) {
            // A database-level helper: db.getCollectionNames(), db.stats(), …
            int paren = rest.indexOf('(');
            if (paren <= 0) return unsupported("Name a collection: db.<collection>.<operation>(…)");
            String helper = rest.substring(0, paren).trim();
            if (!DATABASE_OPS.contains(helper.toLowerCase(Locale.ROOT))) {
                return unsupported("Database-level helper db." + helper + "(…) is not supported here; "
                        + "supported: " + String.join(", ", DATABASE_OPS));
            }
            return new MongoShellCommand(null, helper.toLowerCase(Locale.ROOT), List.of(),
                    null, null, null, null, false, null);
        }
        String collection = rest.substring(0, firstDot);

        List<Call> calls = splitCalls(rest.substring(firstDot + 1));
        if (calls == null) return unsupported("Unbalanced brackets — check the parentheses and braces");
        if (calls.isEmpty()) return unsupported("Name an operation: db." + collection + ".find({})");

        Call head = calls.get(0);
        String operation = head.name().toLowerCase(Locale.ROOT);
        if (!KNOWN_OPS.contains(operation)) {
            return unsupported("db." + collection + "." + head.name() + "(…) is not supported here; "
                    + "supported: " + String.join(", ", KNOWN_OPS));
        }

        String sort = null;
        String projection = head.arguments().size() > 1 && operation.equals("find")
                ? head.arguments().get(1) : null;
        Integer limit = null;
        Integer skip = null;
        boolean count = false;

        for (Call modifier : calls.subList(1, calls.size())) {
            String name = modifier.name().toLowerCase(Locale.ROOT);
            String arg = modifier.arguments().isEmpty() ? "" : modifier.arguments().get(0);
            switch (name) {
                case "sort" -> sort = arg;
                case "limit" -> {
                    Integer parsed = asInt(arg);
                    if (parsed == null) return unsupported(".limit(" + arg + ") needs a number");
                    limit = parsed;
                }
                case "skip" -> {
                    Integer parsed = asInt(arg);
                    if (parsed == null) return unsupported(".skip(" + arg + ") needs a number");
                    skip = parsed;
                }
                case "count", "size" -> count = true;
                case "pretty", "toarray" -> { /* rendering is always pretty here */ }
                case "projection" -> projection = arg;
                default -> {
                    return unsupported("." + modifier.name() + "(…) is not a supported modifier; "
                            + "supported: sort, limit, skip, count, projection, pretty");
                }
            }
        }
        return new MongoShellCommand(collection, operation, head.arguments(),
                sort, projection, limit, skip, count, null);
    }

    /** The query bar equivalent of a parsed {@code find}, so the shell and the bar stay in step. */
    public MongoQuerySpec toQuerySpec(int defaultLimit) {
        return new MongoQuerySpec(firstArgument(),
                projection == null ? "" : projection,
                sort == null ? "" : sort,
                skip == null ? 0 : skip,
                limit == null ? defaultLimit : limit);
    }

    /** One {@code name(args…)} link in the chain. */
    private record Call(String name, List<String> arguments) {}

    /**
     * Splits {@code find({...}).sort({...}).limit(10)} into its calls, respecting nesting and string
     * literals so a brace or a dot inside a value never ends an argument.
     *
     * @return the calls, or {@code null} when the brackets do not balance
     */
    private static List<Call> splitCalls(String text) {
        List<Call> calls = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int open = indexOfTopLevel(text, i, '(');
            if (open < 0) {
                // Trailing text with no call — e.g. "db.people.find({}).x"
                return text.substring(i).isBlank() ? calls : null;
            }
            String name = text.substring(i, open).trim();
            if (name.startsWith(".")) name = name.substring(1).trim();
            int close = matchingClose(text, open);
            if (close < 0) return null;
            calls.add(new Call(name, splitArguments(text.substring(open + 1, close))));
            i = close + 1;
            if (i < text.length() && text.charAt(i) == '.') i++;
        }
        return calls;
    }

    /** The index of {@code target} at nesting depth 0, ignoring string literals. */
    private static int indexOfTopLevel(String text, int from, char target) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == target && depth == 0) return i;
            if (c == '{' || c == '[') depth++;
            if (c == '}' || c == ']') depth--;
        }
        return -1;
    }

    /** The index of the {@code )} closing the {@code (} at {@code open}, or -1 if unbalanced. */
    private static int matchingClose(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'' -> quote = c;
                case '(', '{', '[' -> depth++;
                case ')', '}', ']' -> {
                    depth--;
                    if (depth == 0 && c == ')') return i;
                }
                default -> { }
            }
        }
        return -1;
    }

    /** Splits an argument list on top-level commas, so nested documents stay whole. */
    private static List<String> splitArguments(String text) {
        List<String> args = new ArrayList<>();
        if (text.isBlank()) return args;
        int depth = 0;
        char quote = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < text.length()) { current.append(text.charAt(++i)); }
                else if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '"', '\'' -> { quote = c; current.append(c); }
                case '{', '[', '(' -> { depth++; current.append(c); }
                case '}', ']', ')' -> { depth--; current.append(c); }
                case ',' -> {
                    if (depth == 0) { args.add(current.toString().trim()); current.setLength(0); }
                    else current.append(c);
                }
                default -> current.append(c);
            }
        }
        if (current.length() > 0) args.add(current.toString().trim());
        return args;
    }

    private static Integer asInt(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
