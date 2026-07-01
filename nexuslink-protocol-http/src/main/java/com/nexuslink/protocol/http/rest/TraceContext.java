package com.nexuslink.protocol.http.rest;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free model of the <a href="https://www.w3.org/TR/trace-context/">W3C Trace
 * Context</a> (Level&nbsp;1) distributed-tracing headers, {@code traceparent} and {@code tracestate}.
 * <p>
 * This class carries no networking: it parses/validates the two headers, generates fresh ids from a
 * {@link SecureRandom}, derives child contexts, and injects the pair back into an outgoing header map.
 * All identifiers are lowercase hex as required by the spec.
 *
 * <h2>{@code traceparent}</h2>
 * Format {@code version-traceid-parentid-flags}. For version {@code 00} that is
 * {@code 00-<32 hex>-<16 hex>-<2 hex>}. Rejected inputs: wrong field count, non-(lowercase-)hex
 * fields, an all-zero trace-id, an all-zero parent-id, and — for version {@code 00} — any trailing
 * data after the flags. The parent-id is also known as the <em>span-id</em>; the low bit of the flags
 * ({@code 0x01}) is the <em>sampled</em> flag.
 *
 * <h2>{@code tracestate}</h2>
 * See the nested {@link TraceState}.
 *
 * <p>Instances are immutable; mutating operations return new instances.
 */
public final class TraceContext {

    /** Canonical (lowercase) header name for the traceparent header. */
    public static final String TRACEPARENT = "traceparent";
    /** Canonical (lowercase) header name for the tracestate header. */
    public static final String TRACESTATE = "tracestate";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern HEX2 = Pattern.compile("[0-9a-f]{2}");
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PARENT_ID = Pattern.compile("[0-9a-f]{16}");

    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    /** Sampled bit of the trace-flags byte. */
    public static final int FLAG_SAMPLED = 0x01;

    private final String traceId;
    private final String parentId;
    private final int flags;
    private final TraceState traceState;

    private TraceContext(String traceId, String parentId, int flags, TraceState traceState) {
        this.traceId = traceId;
        this.parentId = parentId;
        this.flags = flags & 0xFF;
        this.traceState = traceState == null ? TraceState.EMPTY : traceState;
    }

    /** The 32-hex-character trace-id, shared by every span in a trace. */
    public String traceId() { return traceId; }

    /** The 16-hex-character parent-id (a.k.a. span-id) of the caller. */
    public String parentId() { return parentId; }

    /** Alias for {@link #parentId()} using the more common "span-id" name. */
    public String spanId() { return parentId; }

    /** The raw trace-flags byte as an int in {@code 0..255}. */
    public int flags() { return flags; }

    /** True when the sampled flag ({@code 0x01}) is set. */
    public boolean isSampled() { return (flags & FLAG_SAMPLED) != 0; }

    /** The associated {@link TraceState} (never {@code null}; may be {@link TraceState#isEmpty() empty}). */
    public TraceState traceState() { return traceState; }

    /** Returns a copy of this context carrying the given (possibly empty) tracestate. */
    public TraceContext withTraceState(TraceState newState) {
        return new TraceContext(traceId, parentId, flags, newState);
    }

    /** Formats this context as a version-{@code 00} traceparent header value. */
    public String formatTraceparent() {
        return "00-" + traceId + "-" + parentId + "-" + hex2(flags);
    }

    /**
     * Parses and validates a {@code traceparent} header value.
     *
     * @return the parsed context (with an empty tracestate)
     * @throws IllegalArgumentException if the value is malformed per the spec
     */
    public static TraceContext parseTraceparent(String value) {
        return tryParseTraceparent(value).orElseThrow(
                () -> new IllegalArgumentException("invalid traceparent: " + value));
    }

    /**
     * Attempts to parse a {@code traceparent} header value.
     *
     * @return the parsed context, or empty if the value is malformed per the spec
     */
    public static Optional<TraceContext> tryParseTraceparent(String value) {
        if (value == null) return Optional.empty();
        String[] parts = value.split("-", -1);
        if (parts.length < 4) return Optional.empty();

        String version = parts[0];
        if (!HEX2.matcher(version).matches()) return Optional.empty();
        // ff is reserved/invalid; version 00 forbids any trailing data.
        if (version.equals("ff")) return Optional.empty();
        if (version.equals("00") && parts.length != 4) return Optional.empty();

        String traceId = parts[1];
        String parentId = parts[2];
        String flagsHex = parts[3];

        if (!TRACE_ID.matcher(traceId).matches() || traceId.equals(ZERO_TRACE_ID)) return Optional.empty();
        if (!PARENT_ID.matcher(parentId).matches() || parentId.equals(ZERO_PARENT_ID)) return Optional.empty();
        if (!HEX2.matcher(flagsHex).matches()) return Optional.empty();

        int flags = Integer.parseInt(flagsHex, 16);
        return Optional.of(new TraceContext(traceId, parentId, flags, TraceState.EMPTY));
    }

    /**
     * Generates a fresh root context: a random 16-byte trace-id and 8-byte parent-id, with the
     * sampled flag set as requested and an empty tracestate.
     */
    public static TraceContext newRootTraceparent(boolean sampled) {
        return new TraceContext(randomHex(16), randomHex(8), sampled ? FLAG_SAMPLED : 0, TraceState.EMPTY);
    }

    /**
     * Derives a child of the given {@code traceparent} header value: same trace-id, a fresh random
     * parent-id, and preserved flags. The returned context has an empty tracestate (a traceparent
     * string carries none); use {@link #child()} to preserve an in-model tracestate.
     *
     * @throws IllegalArgumentException if {@code traceparent} is malformed
     */
    public static TraceContext childOf(String traceparent) {
        return parseTraceparent(traceparent).child();
    }

    /**
     * Derives a child of this context: same trace-id and flags, a fresh random parent-id, and the
     * same tracestate.
     */
    public TraceContext child() {
        return new TraceContext(traceId, randomHex(8), flags, traceState);
    }

    /**
     * Injects this context into an outgoing header map: always sets {@code traceparent}, and sets
     * {@code tracestate} only when non-empty.
     */
    public void injectInto(Map<String, String> headers) {
        headers.put(TRACEPARENT, formatTraceparent());
        if (!traceState.isEmpty()) {
            headers.put(TRACESTATE, traceState.format());
        }
    }

    /**
     * Extracts a context from an incoming header map, reading {@code traceparent} and (if present and
     * valid) {@code tracestate}.
     *
     * @return the parsed context, or empty if {@code traceparent} is missing or malformed
     */
    public static Optional<TraceContext> extract(Map<String, String> headers) {
        if (headers == null) return Optional.empty();
        return tryParseTraceparent(headers.get(TRACEPARENT))
                .map(ctx -> ctx.withTraceState(TraceState.parse(headers.get(TRACESTATE))));
    }

    private static String hex2(int b) {
        int v = b & 0xFF;
        return "" + Character.forDigit((v >> 4) & 0xF, 16) + Character.forDigit(v & 0xF, 16);
    }

    private static String randomHex(int numBytes) {
        byte[] bytes = new byte[numBytes];
        // All-zero ids are invalid; the odds are astronomically small but the loop keeps it correct.
        do {
            RANDOM.nextBytes(bytes);
        } while (isAllZero(bytes));
        StringBuilder sb = new StringBuilder(numBytes * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    /**
     * A parsed, ordered {@code tracestate} list of at most 32 {@code key=value} members. The
     * left-most member is the most recently mutated. Instances are immutable; {@link #with} returns a
     * new instance.
     *
     * <h3>Validity</h3>
     * A member key is either a simple key {@code [a-z][a-z0-9_/*-]{0,255}} or a multi-tenant key
     * {@code <tenant>@<system>}; a value is 1–256 printable characters excluding {@code ,} and
     * {@code =} and not ending in a space. Optional whitespace around members is trimmed and
     * whitespace-only members are ignored. If any member is malformed, or a key repeats, the whole
     * list is dropped ({@link #parse} returns {@link #EMPTY}) as the spec requires.
     */
    public static final class TraceState {

        /** The empty tracestate (no members). */
        public static final TraceState EMPTY = new TraceState(List.of());

        /** Maximum number of members permitted by the spec. */
        public static final int MAX_MEMBERS = 32;

        private static final Pattern KEY = Pattern.compile(
                "[a-z][a-z0-9_*/-]{0,255}"
                        + "|[a-z0-9][a-z0-9_*/-]{0,240}@[a-z][a-z0-9_*/-]{0,13}");

        private final List<Member> members;

        private TraceState(List<Member> members) {
            this.members = members;
        }

        /**
         * Parses a {@code tracestate} header value. Returns {@link #EMPTY} for {@code null}, blank, or
         * any spec-invalid input (the entire list is dropped rather than partially accepted).
         */
        public static TraceState parse(String value) {
            if (value == null || value.isBlank()) return EMPTY;
            String[] raw = value.split(",", -1);
            List<Member> parsed = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (String entry : raw) {
                String trimmed = stripOws(entry);
                if (trimmed.isEmpty()) continue; // whitespace-only members are allowed and ignored
                int eq = trimmed.indexOf('=');
                if (eq <= 0) return EMPTY;
                String key = trimmed.substring(0, eq);
                String memberValue = trimmed.substring(eq + 1);
                if (!isValidKey(key) || !isValidValue(memberValue)) return EMPTY;
                if (seen.contains(key)) return EMPTY; // duplicate keys are invalid
                seen.add(key);
                parsed.add(new Member(key, memberValue));
            }
            if (parsed.isEmpty()) return EMPTY;
            if (parsed.size() > MAX_MEMBERS) return EMPTY;
            return new TraceState(List.copyOf(parsed));
        }

        /** Formats this tracestate as a canonical, comma-separated header value (no extra whitespace). */
        public String format() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) sb.append(',');
                Member m = members.get(i);
                sb.append(m.key()).append('=').append(m.value());
            }
            return sb.toString();
        }

        /** The members in order (left-most = most recent); unmodifiable. */
        public List<Member> members() {
            return Collections.unmodifiableList(members);
        }

        /** True when there are no members. */
        public boolean isEmpty() { return members.isEmpty(); }

        /** The number of members. */
        public int size() { return members.size(); }

        /** The value for {@code key}, if present. */
        public Optional<String> get(String key) {
            for (Member m : members) {
                if (m.key().equals(key)) return Optional.of(m.value());
            }
            return Optional.empty();
        }

        /**
         * Adds or updates a vendor member and moves it to the front (left-most, i.e. most recent),
         * evicting the oldest (right-most) members to stay within {@link #MAX_MEMBERS}.
         *
         * @return a new tracestate; this instance is unchanged
         * @throws IllegalArgumentException if {@code key} or {@code value} is invalid per the spec
         */
        public TraceState with(String key, String value) {
            if (!isValidKey(key)) throw new IllegalArgumentException("invalid tracestate key: " + key);
            if (!isValidValue(value)) throw new IllegalArgumentException("invalid tracestate value: " + value);
            List<Member> next = new ArrayList<>(members.size() + 1);
            next.add(new Member(key, value));
            for (Member m : members) {
                if (!m.key().equals(key)) next.add(m);
            }
            while (next.size() > MAX_MEMBERS) {
                next.remove(next.size() - 1);
            }
            return new TraceState(List.copyOf(next));
        }

        private static boolean isValidKey(String key) {
            return key != null && KEY.matcher(key).matches();
        }

        private static boolean isValidValue(String value) {
            if (value == null) return false;
            int len = value.length();
            if (len < 1 || len > 256) return false;
            for (int i = 0; i < len; i++) {
                char c = value.charAt(i);
                // chr = %x20-2B / %x2D-3C / %x3E-7E : printable ASCII minus ',' (0x2C) and '=' (0x3D).
                boolean allowed = (c >= 0x20 && c <= 0x2B) || (c >= 0x2D && c <= 0x3C) || (c >= 0x3E && c <= 0x7E);
                if (!allowed) return false;
            }
            // The value must not end with a space.
            return value.charAt(len - 1) != ' ';
        }

        private static String stripOws(String s) {
            int start = 0;
            int end = s.length();
            while (start < end && isOws(s.charAt(start))) start++;
            while (end > start && isOws(s.charAt(end - 1))) end--;
            return s.substring(start, end);
        }

        private static boolean isOws(char c) {
            return c == ' ' || c == '\t';
        }

        /** One {@code key=value} tracestate member. */
        public record Member(String key, String value) {}
    }
}
