package com.nexuslink.protocol.ldap;

import java.util.List;
import java.util.Objects;

/**
 * Typed, immutable model of an RFC 4515 LDAP search filter. Instances are produced by
 * {@link LdapFilterParser#parse(String)} and can {@link #render()} themselves back into a valid,
 * equivalent filter string (a parse&nbsp;&rarr;&nbsp;render round-trip).
 *
 * <p>This is the inverse of {@link LdapFilterBuilder}, which composes filter strings from simple
 * rows. Assertion values are held in <em>decoded</em> form (all {@code \HH} escapes from RFC 4515
 * §3 resolved) and are re-escaped on {@link #render()} using the same rule as the builder
 * ({@code * ( ) \ NUL} &rarr; {@code \HH}), so a rendered filter is always well-formed.
 *
 * <p>Grammar reference (RFC 4515 §2):
 * <pre>
 *   filter     = "(" filtercomp ")"
 *   filtercomp = and / or / not / item
 *   and        = "&amp;" filterlist
 *   or         = "|" filterlist
 *   not        = "!" filter
 *   item       = simple / present / substring / extensible
 * </pre>
 */
public sealed interface LdapFilter {

    /** Render this node as a valid RFC 4515 filter string (escaping assertion values per §3). */
    String render();

    // --- boolean combinators (RFC 4515 §2) -------------------------------------------------------

    /** Conjunction {@code (&(...)(...))} — matches when every child matches (RFC 4515 §2). */
    record And(List<LdapFilter> children) implements LdapFilter {
        public And {
            Objects.requireNonNull(children, "children");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("AND requires at least one child filter");
            }
        }

        @Override
        public String render() {
            return renderList('&', children);
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Disjunction {@code (|(...)(...))} — matches when any child matches (RFC 4515 §2). */
    record Or(List<LdapFilter> children) implements LdapFilter {
        public Or {
            Objects.requireNonNull(children, "children");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("OR requires at least one child filter");
            }
        }

        @Override
        public String render() {
            return renderList('|', children);
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Negation {@code (!(...))} — matches when the single child does not (RFC 4515 §2). */
    record Not(LdapFilter child) implements LdapFilter {
        public Not {
            Objects.requireNonNull(child, "child");
        }

        @Override
        public String render() {
            return "(!" + child.render() + ")";
        }

        @Override
        public String toString() {
            return render();
        }
    }

    // --- item filters (RFC 4515 §2) --------------------------------------------------------------

    /** Presence test {@code (attr=*)} — the attribute has at least one value (RFC 4515 §2). */
    record Present(String attribute) implements LdapFilter {
        public Present {
            Objects.requireNonNull(attribute, "attribute");
        }

        @Override
        public String render() {
            return "(" + attribute + "=*)";
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Equality match {@code (attr=value)} (RFC 4515 §2, "simple" with filtertype {@code =}). */
    record Equality(String attribute, String value) implements LdapFilter {
        public Equality {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String render() {
            return "(" + attribute + "=" + escape(value) + ")";
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /**
     * Substring match {@code (attr=ini*any*fin)} (RFC 4515 §2). {@code initial} and {@code fin} may
     * be empty (leading/trailing {@code *}); {@code any} holds the middle fragments in order.
     */
    record Substring(String attribute, String initial, List<String> any, String fin)
            implements LdapFilter {
        public Substring {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(initial, "initial");
            Objects.requireNonNull(fin, "fin");
            any = List.copyOf(any == null ? List.of() : any);
        }

        @Override
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append('(').append(attribute).append('=');
            sb.append(escape(initial));
            for (String part : any) {
                sb.append('*').append(escape(part));
            }
            sb.append('*');
            sb.append(escape(fin));
            sb.append(')');
            return sb.toString();
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Ordering match {@code (attr>=value)} (RFC 4515 §2, filtertype {@code >=}). */
    record GreaterOrEqual(String attribute, String value) implements LdapFilter {
        public GreaterOrEqual {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String render() {
            return "(" + attribute + ">=" + escape(value) + ")";
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Ordering match {@code (attr<=value)} (RFC 4515 §2, filtertype {@code <=}). */
    record LessOrEqual(String attribute, String value) implements LdapFilter {
        public LessOrEqual {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String render() {
            return "(" + attribute + "<=" + escape(value) + ")";
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /** Approximate match {@code (attr~=value)} (RFC 4515 §2, filtertype {@code ~=}). */
    record Approx(String attribute, String value) implements LdapFilter {
        public Approx {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String render() {
            return "(" + attribute + "~=" + escape(value) + ")";
        }

        @Override
        public String toString() {
            return render();
        }
    }

    /**
     * Extensible match {@code (attr:dn:rule:=value)} (RFC 4515 §2, {@code extensible}). Either
     * {@code attribute} or {@code matchingRule} may be {@code null}, but not both; {@code dnAttributes}
     * reflects the optional {@code :dn} flag (RFC 4511 §4.5.1.7).
     */
    record ExtensibleMatch(String attribute, String matchingRule, boolean dnAttributes, String value)
            implements LdapFilter {
        public ExtensibleMatch {
            Objects.requireNonNull(value, "value");
            if (attribute == null && matchingRule == null) {
                throw new IllegalArgumentException(
                        "extensible match requires an attribute or a matching rule");
            }
        }

        @Override
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append('(');
            if (attribute != null) {
                sb.append(attribute);
            }
            if (dnAttributes) {
                sb.append(":dn");
            }
            if (matchingRule != null) {
                sb.append(':').append(matchingRule);
            }
            sb.append(":=").append(escape(value)).append(')');
            return sb.toString();
        }

        @Override
        public String toString() {
            return render();
        }
    }

    // --- shared rendering helpers ----------------------------------------------------------------

    private static String renderList(char op, List<LdapFilter> children) {
        StringBuilder sb = new StringBuilder();
        sb.append('(').append(op);
        for (LdapFilter child : children) {
            sb.append(child.render());
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Escape an assertion value per RFC 4515 §3: each of {@code * ( ) \} and the NUL character is
     * replaced by a backslash followed by its two lowercase hex digits. All other characters pass
     * through unchanged. This mirrors {@link LdapFilterBuilder#escape(String)}.
     */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\\' -> sb.append("\\5c");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
