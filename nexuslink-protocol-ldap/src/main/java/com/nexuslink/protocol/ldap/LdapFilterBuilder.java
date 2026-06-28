package com.nexuslink.protocol.ldap;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure, offline composer of RFC 4515 search filters from simple (attribute, operator, value) rows.
 * No directory connection is involved — this is the tested logic behind the UI's filter-builder
 * dialog. Assertion values are escaped per RFC 4515 §3 ({@code * ( ) \ NUL} → {@code \HH}); the
 * wildcards implied by {@code contains}/{@code starts-with} are added <em>after</em> escaping so a
 * literal {@code *} in a value never turns into a wildcard.
 */
public final class LdapFilterBuilder {

    private LdapFilterBuilder() {
    }

    /** How an attribute is matched against a value when rendering a {@link Condition}. */
    public enum Operator {
        /** {@code (attr=value)} — exact equality. */
        EQUALS,
        /** {@code (attr=*value*)} — substring containment. */
        CONTAINS,
        /** {@code (attr=value*)} — substring prefix. */
        STARTS_WITH,
        /** {@code (attr=*)} — attribute present (value ignored). */
        PRESENT,
        /** {@code (attr>=value)} — greater-than-or-equal ordering match. */
        GREATER_OR_EQUAL,
        /** {@code (attr<=value)} — less-than-or-equal ordering match. */
        LESS_OR_EQUAL
    }

    /** One filter row: an attribute matched against {@code value} using {@code operator}. */
    public record Condition(String attribute, Operator operator, String value) {
        public Condition {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(operator, "operator");
        }

        /** Convenience factory; a {@code null} value is treated as empty. */
        public static Condition of(String attribute, Operator operator, String value) {
            return new Condition(attribute, operator, value == null ? "" : value);
        }
    }

    /**
     * Compose a filter from {@code conditions}, joined with AND ({@code &}) when {@code and} is true,
     * otherwise OR ({@code |}). Blank-attribute rows are skipped. A single effective condition is
     * returned bare (no enclosing {@code &}/{@code |}); zero conditions yield the present-everything
     * filter {@code (objectClass=*)}.
     */
    public static String build(List<Condition> conditions, boolean and) {
        Objects.requireNonNull(conditions, "conditions");
        StringBuilder joined = new StringBuilder();
        int count = 0;
        for (Condition c : conditions) {
            if (c == null || c.attribute() == null || c.attribute().isBlank()) {
                continue;
            }
            joined.append(render(c));
            count++;
        }
        if (count == 0) {
            return "(objectClass=*)";
        }
        if (count == 1) {
            return joined.toString();
        }
        return "(" + (and ? '&' : '|') + joined + ")";
    }

    /** Render a single condition to its parenthesised RFC 4515 form. */
    public static String render(Condition c) {
        Objects.requireNonNull(c, "condition");
        String attr = c.attribute().trim();
        String value = c.value() == null ? "" : c.value();
        return switch (c.operator()) {
            case EQUALS -> "(" + attr + "=" + escape(value) + ")";
            case CONTAINS -> "(" + attr + "=*" + escape(value) + "*)";
            case STARTS_WITH -> "(" + attr + "=" + escape(value) + "*)";
            case PRESENT -> "(" + attr + "=*)";
            case GREATER_OR_EQUAL -> "(" + attr + ">=" + escape(value) + ")";
            case LESS_OR_EQUAL -> "(" + attr + "<=" + escape(value) + ")";
        };
    }

    /**
     * Escape an assertion value per RFC 4515 §3: each of {@code * ( ) \} and the NUL character is
     * replaced by a backslash followed by its two-digit hex code. All other characters pass through.
     */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
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

    // --- predefined filters ----------------------------------------------------------------------

    /** Matches all person entries: {@code (objectClass=person)}. */
    public static String allPersons() {
        return "(objectClass=person)";
    }

    /** Matches all group entries (groupOfNames or groupOfUniqueNames or AD group). */
    public static String allGroups() {
        return "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=group))";
    }

    /** Matches all organizational units: {@code (objectClass=organizationalUnit)}. */
    public static String allOrganizationalUnits() {
        return "(objectClass=organizationalUnit)";
    }

    /** Matches a single account by uid (value escaped). */
    public static String byUid(String uid) {
        return "(uid=" + escape(uid == null ? "" : uid.trim()) + ")";
    }

    /** Matches a single account by common name (value escaped). */
    public static String byCommonName(String cn) {
        return "(cn=" + escape(cn == null ? "" : cn.trim()) + ")";
    }

    /** Parse a textual operator label (as shown in the UI) back to an {@link Operator}. */
    public static Operator operatorOf(String label) {
        if (label == null) {
            return Operator.EQUALS;
        }
        return switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "contains" -> Operator.CONTAINS;
            case "starts-with", "starts with", "startswith" -> Operator.STARTS_WITH;
            case "present", "exists" -> Operator.PRESENT;
            case ">=", "gte", "greater-or-equal" -> Operator.GREATER_OR_EQUAL;
            case "<=", "lte", "less-or-equal" -> Operator.LESS_OR_EQUAL;
            default -> Operator.EQUALS;
        };
    }
}
