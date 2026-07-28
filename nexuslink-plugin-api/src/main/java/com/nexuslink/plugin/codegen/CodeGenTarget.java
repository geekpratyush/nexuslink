package com.nexuslink.plugin.codegen;

import java.util.Objects;

/**
 * One output target a {@link CodeGenerator} can render — a language or tool, e.g. {@code curl},
 * {@code java}, {@code python}.
 *
 * @param id      stable identifier, unique within its generator (used for lookup, never displayed)
 * @param label   what the language dropdown shows, e.g. {@code "Python (requests)"}
 * @param syntax  a coarse syntax hint for a viewer/highlighter, e.g. {@code "python"}, {@code "shell"}
 */
public record CodeGenTarget(String id, String label, String syntax) {

    public CodeGenTarget {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("a code-gen target id must not be blank");
        label = (label == null || label.isBlank()) ? id : label;
        syntax = (syntax == null || syntax.isBlank()) ? "text" : syntax;
    }

    /** A target whose label doubles as its display text and whose syntax is plain text. */
    public static CodeGenTarget of(String id, String label) {
        return new CodeGenTarget(id, label, "text");
    }

    @Override
    public String toString() {
        return label;
    }
}
