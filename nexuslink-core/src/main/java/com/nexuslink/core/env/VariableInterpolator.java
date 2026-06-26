package com.nexuslink.core.env;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Substitutes {@code ${VAR}} placeholders in any string field using a pluggable resolver (normally
 * {@link EnvironmentService}). Supports:
 *
 * <ul>
 *   <li>{@code ${VAR}} — replaced by the resolved value; left untouched if the name is unknown, so
 *       the user can see exactly which references didn't resolve.</li>
 *   <li>{@code ${VAR:-default}} — uses {@code default} when {@code VAR} is unset or empty.</li>
 *   <li>{@code $$} — an escape for a literal {@code $} (so {@code $${VAR}} renders {@code ${VAR}}).</li>
 *   <li>Nested references — a resolved value (or default) is itself interpolated, with a cycle guard
 *       so {@code A=${B}}, {@code B=${A}} can't loop forever.</li>
 * </ul>
 */
public final class VariableInterpolator {

    private VariableInterpolator() {}

    /** Interpolates {@code template}; a {@code null} template returns {@code null}. */
    public static String interpolate(String template, Function<String, String> resolver) {
        if (template == null || template.indexOf('$') < 0) return template;
        return expand(template, resolver, new LinkedHashSet<>());
    }

    private static String expand(String template, Function<String, String> resolver, Set<String> visiting) {
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '$' && i + 1 < n && template.charAt(i + 1) == '$') {
                out.append('$');        // "$$" -> literal "$"; a following "{...}" stays literal text
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < n && template.charAt(i + 1) == '{') {
                int close = template.indexOf('}', i + 2);
                if (close < 0) {                 // unterminated "${" — emit the rest verbatim
                    out.append(template, i, n);
                    break;
                }
                String expr = template.substring(i + 2, close);
                out.append(resolveExpr(expr, resolver, visiting));
                i = close + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String resolveExpr(String expr, Function<String, String> resolver, Set<String> visiting) {
        String name = expr;
        String defaultValue = null;
        int sep = expr.indexOf(":-");
        if (sep >= 0) {
            name = expr.substring(0, sep);
            defaultValue = expr.substring(sep + 2);
        }
        name = name.trim();

        String raw = name.isEmpty() ? null : resolver.apply(name);
        boolean missing = (raw == null) || (defaultValue != null && raw.isEmpty());
        if (missing) {
            if (defaultValue == null) return "${" + expr + "}";  // unknown, no default → keep literal
            raw = defaultValue;
        }

        if (raw.indexOf('$') < 0) return raw;
        if (!visiting.add(name)) return raw;                     // cycle → stop expanding this branch
        try {
            return expand(raw, resolver, visiting);
        } finally {
            visiting.remove(name);
        }
    }
}
