package com.nexuslink.protocol.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A stored-procedure or function call, described independently of any JDBC connection: the routine
 * name, whether it returns a value, and its parameters in declaration order. The class renders the
 * JDBC escape syntax — {@code {call proc(?, ?)}} for a procedure, {@code {? = call fn(?)}} for a
 * function — which every driver understands, so no dialect-specific {@code EXEC} / {@code CALL}
 * spelling is needed.
 *
 * <p>Being pure it is fully unit-testable: {@link #sql()} and {@link #inputs()} can be checked
 * without a database, and {@link JdbcService#call} simply binds what this class describes.
 */
public final class CallableSpec {

    /** How a parameter is passed: into the routine, out of it, or both. */
    public enum Direction {
        IN, OUT, INOUT;

        /** {@code true} when the caller supplies a value for this parameter. */
        public boolean isInput() { return this != OUT; }

        /** {@code true} when the routine writes a value back through this parameter. */
        public boolean isOutput() { return this != IN; }

        /** Parses a direction name case-insensitively, defaulting to {@link #IN}. */
        public static Direction parse(String s) {
            if (s == null) return IN;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "OUT" -> OUT;
                case "INOUT", "IN OUT", "IN/OUT" -> INOUT;
                default -> IN;
            };
        }
    }

    /**
     * One parameter of the call. {@code name} is for display only (drivers differ on named binding,
     * so binding is positional); {@code sqlType} is a {@link java.sql.Types} constant used to
     * register an OUT parameter; {@code value} is the IN value as text, {@code null} meaning SQL
     * NULL. {@code typeLabel} is the database's own type name, shown in the parameter form.
     */
    public record Param(String name, Direction direction, int sqlType, String typeLabel, String value) {

        /** An IN parameter carrying {@code value} (null → SQL NULL). */
        public static Param in(String name, String value) {
            return new Param(name, Direction.IN, java.sql.Types.VARCHAR, "varchar", value);
        }

        /** An OUT parameter of the given {@link java.sql.Types} constant. */
        public static Param out(String name, int sqlType, String typeLabel) {
            return new Param(name, Direction.OUT, sqlType, typeLabel, null);
        }

        /** This parameter with a different IN value, leaving everything else alone. */
        public Param withValue(String newValue) {
            return new Param(name, direction, sqlType, typeLabel, newValue);
        }
    }

    private final String routine;
    private final boolean function;
    private final List<Param> params;

    private CallableSpec(String routine, boolean function, List<Param> params) {
        this.routine = routine;
        this.function = function;
        this.params = List.copyOf(params);
    }

    /** A procedure call — no return value, every parameter listed inside the parentheses. */
    public static CallableSpec procedure(String routine, List<Param> params) {
        return new CallableSpec(require(routine), false, params);
    }

    /**
     * A function call — the first parameter is the return value and must be an OUT parameter; it is
     * rendered as the leading {@code ? =} rather than inside the argument list.
     */
    public static CallableSpec function(String routine, List<Param> params) {
        if (params.isEmpty() || !params.get(0).direction().isOutput()) {
            throw new IllegalArgumentException("a function's first parameter must be its OUT return value");
        }
        return new CallableSpec(require(routine), true, params);
    }

    private static String require(String routine) {
        if (routine == null || routine.isBlank()) throw new IllegalArgumentException("routine name is required");
        return routine.trim();
    }

    public String routine() { return routine; }
    public boolean isFunction() { return function; }

    /** The parameters in declaration order; for a function the first is the return value. */
    public List<Param> params() { return params; }

    /** The parameters the caller supplies a value for, in binding order. */
    public List<Param> inputs() {
        List<Param> in = new ArrayList<>();
        for (Param p : params) if (p.direction().isInput()) in.add(p);
        return in;
    }

    /** The parameters the routine writes back, in binding order. */
    public List<Param> outputs() {
        List<Param> out = new ArrayList<>();
        for (Param p : params) if (p.direction().isOutput()) out.add(p);
        return out;
    }

    /** The JDBC escape-syntax call string, with one {@code ?} placeholder per parameter. */
    public String sql() {
        int args = function ? params.size() - 1 : params.size();
        StringBuilder sb = new StringBuilder("{");
        if (function) sb.append("? = ");
        sb.append("call ").append(routine).append('(');
        for (int i = 0; i < args; i++) sb.append(i > 0 ? ", ?" : "?");
        return sb.append(")}").toString();
    }

    /** This spec with the IN values replaced positionally by {@code values} (nulls kept as NULL). */
    public CallableSpec withValues(List<String> values) {
        List<Param> updated = new ArrayList<>(params.size());
        int i = 0;
        for (Param p : params) {
            if (p.direction().isInput() && i < values.size()) updated.add(p.withValue(values.get(i++)));
            else updated.add(p);
        }
        return new CallableSpec(routine, function, updated);
    }

    @Override public String toString() { return sql(); }
}
