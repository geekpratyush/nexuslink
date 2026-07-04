package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Computes the new names for a multi-file rename, the way a commander's "batch rename" dialog does:
 * an optional find/replace (literal or regex), prefix/suffix, sequential numbering via a {@code {n}}
 * token, and a case transform — all applied to the base name while the extension is preserved. It is
 * a pure preview: it never touches the filesystem, so the UI can show the before → after table (and
 * flag colliding targets) before the user commits. JavaFX-free and unit-testable, mirroring
 * {@link DirectoryDiff} and {@link SyncPlanner}.
 */
public final class BulkRename {

    /** How to recase the resulting base name. */
    public enum Case { KEEP, LOWER, UPPER }

    /**
     * The transform to apply. Any string field may be blank to skip that step. {@code find}/{@code replace}
     * is applied first (as a regex when {@code regex} is true, else a literal replace-all), then
     * {@code prefix}/{@code suffix} wrap the result, then every occurrence of {@code numberToken} is
     * replaced by the running counter (start/step, zero-padded to {@code pad} digits), then {@code nameCase}
     * recases the base. The extension (text after the last dot, when not a leading dot) is preserved.
     */
    public record Rule(String find, String replace, boolean regex,
                       String prefix, String suffix,
                       String numberToken, int start, int step, int pad,
                       Case nameCase) {

        /** A no-op rule callers can copy from with {@code with*} intent (all steps skipped). */
        public static Rule identity() {
            return new Rule("", "", false, "", "", "", 1, 1, 0, Case.KEEP);
        }
    }

    /** One before → after row. {@code collision} is true when another row targets the same name. */
    public record Result(String from, String to, boolean collision) {}

    private BulkRename() {}

    /**
     * Builds the before → after list for {@code names} in order (the counter increments per input,
     * regardless of whether that name actually contains the number token). A {@code null}/invalid
     * regex leaves names unchanged for the find/replace step rather than throwing.
     */
    public static List<Result> preview(List<String> names, Rule rule) {
        Pattern pattern = compileFind(rule);
        Map<String, Integer> targetCounts = new HashMap<>();

        List<String> targets = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String to = rename(names.get(i), rule, pattern, rule.start() + i * rule.step());
            targets.add(to);
            targetCounts.merge(to, 1, Integer::sum);
        }

        List<Result> out = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String to = targets.get(i);
            out.add(new Result(names.get(i), to, targetCounts.get(to) > 1));
        }
        return out;
    }

    private static String rename(String name, Rule rule, Pattern find, int counter) {
        int dot = extensionDot(name);
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);   // includes the leading '.'

        if (find != null) {
            String replace = nz(rule.replace());
            // Literal mode replaces verbatim; regex mode honours $1/${name} backreferences.
            base = find.matcher(base).replaceAll(rule.regex() ? replace : java.util.regex.Matcher.quoteReplacement(replace));
        }
        base = nz(rule.prefix()) + base + nz(rule.suffix());
        if (rule.numberToken() != null && !rule.numberToken().isEmpty() && base.contains(rule.numberToken())) {
            String num = rule.pad() > 0 ? String.format("%0" + rule.pad() + "d", counter) : Integer.toString(counter);
            base = base.replace(rule.numberToken(), num);
        }
        base = switch (rule.nameCase()) {
            case KEEP -> base;
            case LOWER -> base.toLowerCase();
            case UPPER -> base.toUpperCase();
        };
        return base + ext;
    }

    /** Index of the extension dot, or -1 when there is none (also -1 for dotfiles like ".bashrc"). */
    private static int extensionDot(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? -1 : dot;   // <=0 skips a leading dot (whole thing is the base)
    }

    /** Compiles the find pattern (literal or regex); returns null when there is nothing to find. */
    private static Pattern compileFind(Rule rule) {
        String find = rule.find();
        if (find == null || find.isEmpty()) return null;
        if (!rule.regex()) return Pattern.compile(Pattern.quote(find));
        try {
            return Pattern.compile(find);
        } catch (PatternSyntaxException e) {
            return null;   // invalid regex → skip the step rather than blow up the preview
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
