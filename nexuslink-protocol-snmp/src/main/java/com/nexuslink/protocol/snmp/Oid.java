package com.nexuslink.protocol.snmp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An immutable numeric SNMP object identifier: an ordered sequence of non-negative sub-identifiers
 * such as {@code 1.3.6.1.2.1.1.1.0}. This is the value type behind the numeric-OID arithmetic that
 * SNMP walking (GETNEXT) needs — lexicographic ordering, prefix tests and successor computation —
 * complementing {@link OidRegistry}, which handles symbolic name resolution.
 *
 * <p>Everything here is pure, dependency-free and side-effect free: instances are deeply immutable
 * (the backing array is never exposed) and safe to share across threads. Sub-identifiers are stored
 * as {@code long} because SNMP allows values up to 2<sup>32</sup>-1, which does not fit an
 * {@code int}.
 *
 * <p><b>Ordering.</b> {@link #compareTo} is the SNMP lexicographic order: compare sub-identifier by
 * sub-identifier from the left; the first differing position decides, and if one OID is a strict
 * prefix of the other the shorter one sorts first. So
 * {@code 1.3.6.1.2.1.1.1 < 1.3.6.1.2.1.1.1.0 < 1.3.6.1.2.1.1.2}.
 *
 * <p><b>Successor.</b> {@link #next()} returns this OID with a trailing {@code .0} appended. That is
 * the conventional "just past this node" bound used to seed a GETNEXT walk: for any OID {@code x},
 * {@code x < x.next()} and there is no OID strictly between them that is not itself a descendant of
 * {@code x}, so starting a walk at {@code x.next()} enumerates the whole subtree rooted at {@code x}.
 */
public final class Oid implements Comparable<Oid> {

    /** Largest legal sub-identifier: SNMP sub-identifiers are unsigned 32-bit (2^32 - 1). */
    public static final long MAX_SUB_ID = 0xFFFFFFFFL;

    /** The sub-identifiers, left to right. Never mutated after construction and never exposed. */
    private final long[] ids;

    private Oid(long[] ids) {
        this.ids = ids;
    }

    /**
     * Parses a dotted OID such as {@code "1.3.6.1.2.1.1.1.0"} into an {@link Oid}. A single optional
     * leading dot is accepted ({@code ".1.3.6"} parses the same as {@code "1.3.6"}). Every component
     * must be a non-negative decimal integer no larger than {@link #MAX_SUB_ID}.
     *
     * @throws OidFormatException if {@code text} is {@code null}, blank, has an empty component
     *     (e.g. {@code "1..2"} or a trailing dot), or a component that is negative, non-numeric or
     *     out of range.
     */
    public static Oid parse(String text) {
        if (text == null) {
            throw new OidFormatException("OID text must not be null", null);
        }
        String norm = text.trim();
        if (norm.startsWith(".")) {
            norm = norm.substring(1);
        }
        if (norm.isEmpty()) {
            throw new OidFormatException("OID must have at least one sub-identifier: \"" + text + "\"", text);
        }
        // Split keeping trailing empties so a trailing dot ("1.3.") is caught as an empty component.
        String[] parts = norm.split("\\.", -1);
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parseComponent(parts[i], text);
        }
        return new Oid(out);
    }

    private static long parseComponent(String part, String original) {
        if (part.isEmpty()) {
            throw new OidFormatException("empty sub-identifier in OID \"" + original + "\"", original);
        }
        long value;
        try {
            value = Long.parseLong(part);
        } catch (NumberFormatException e) {
            throw new OidFormatException(
                    "sub-identifier \"" + part + "\" is not a non-negative integer in OID \"" + original + "\"",
                    original);
        }
        if (value < 0) {
            throw new OidFormatException(
                    "sub-identifier \"" + part + "\" is negative in OID \"" + original + "\"", original);
        }
        if (value > MAX_SUB_ID) {
            throw new OidFormatException(
                    "sub-identifier \"" + part + "\" exceeds the 32-bit maximum in OID \"" + original + "\"",
                    original);
        }
        return value;
    }

    /**
     * Builds an {@link Oid} directly from sub-identifiers. Each must be in {@code [0, MAX_SUB_ID]} and
     * at least one must be supplied.
     *
     * @throws OidFormatException if no sub-identifiers are given or any is out of range.
     */
    public static Oid of(long... subIds) {
        if (subIds == null || subIds.length == 0) {
            throw new OidFormatException("OID must have at least one sub-identifier", null);
        }
        long[] copy = subIds.clone();
        for (long id : copy) {
            if (id < 0 || id > MAX_SUB_ID) {
                throw new OidFormatException("sub-identifier " + id + " is out of the range [0, " + MAX_SUB_ID + "]", null);
            }
        }
        return new Oid(copy);
    }

    /** The number of sub-identifiers in this OID (always at least one). */
    public int length() {
        return ids.length;
    }

    /**
     * The sub-identifier at {@code index} (0-based, left to right).
     *
     * @throws IndexOutOfBoundsException if {@code index} is outside {@code [0, length())}.
     */
    public long get(int index) {
        return ids[index];
    }

    /** An immutable {@link List} view of the sub-identifiers, in order. */
    public List<Long> toList() {
        List<Long> list = new ArrayList<>(ids.length);
        for (long id : ids) {
            list.add(id);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * A new OID consisting of sub-identifiers {@code [from, to)}. Semantics mirror
     * {@link java.util.List#subList}.
     *
     * @throws IndexOutOfBoundsException if the range is invalid.
     * @throws OidFormatException if the range is empty (an OID needs at least one sub-identifier).
     */
    public Oid sub(int from, int to) {
        if (from < 0 || to > ids.length || from > to) {
            throw new IndexOutOfBoundsException("sub range [" + from + ", " + to + ") outside [0, " + ids.length + ")");
        }
        if (from == to) {
            throw new OidFormatException("sub range [" + from + ", " + to + ") is empty; an OID needs a sub-identifier", null);
        }
        return new Oid(Arrays.copyOfRange(ids, from, to));
    }

    /**
     * A new OID with {@code more} appended to this one, e.g. {@code Oid.parse("1.3.6").child(1, 0)} is
     * {@code 1.3.6.1.0}. Calling with no arguments returns this OID unchanged.
     *
     * @throws OidFormatException if any appended sub-identifier is out of {@code [0, MAX_SUB_ID]}.
     */
    public Oid child(long... more) {
        if (more == null || more.length == 0) {
            return this;
        }
        long[] out = Arrays.copyOf(ids, ids.length + more.length);
        for (int i = 0; i < more.length; i++) {
            long id = more[i];
            if (id < 0 || id > MAX_SUB_ID) {
                throw new OidFormatException("sub-identifier " + id + " is out of the range [0, " + MAX_SUB_ID + "]", null);
            }
            out[ids.length + i] = id;
        }
        return new Oid(out);
    }

    /**
     * This OID with its last sub-identifier removed, e.g. the parent of {@code 1.3.6.1} is
     * {@code 1.3.6}. A single-component OID has no parent.
     *
     * @throws OidFormatException if this OID has only one sub-identifier.
     */
    public Oid parent() {
        if (ids.length == 1) {
            throw new OidFormatException("a single-component OID has no parent: \"" + this + "\"", toString());
        }
        return new Oid(Arrays.copyOf(ids, ids.length - 1));
    }

    /**
     * True when this OID is a prefix of {@code other} — i.e. {@code other} is this node or a
     * descendant of it. An OID is a prefix of itself. Used by a walk to detect when GETNEXT has
     * stepped outside the requested subtree.
     */
    public boolean isPrefixOf(Oid other) {
        if (other == null || other.ids.length < ids.length) {
            return false;
        }
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != other.ids[i]) {
                return false;
            }
        }
        return true;
    }

    /** The mirror of {@link #isPrefixOf}: true when {@code prefix} is a prefix of this OID. */
    public boolean startsWith(Oid prefix) {
        return prefix != null && prefix.isPrefixOf(this);
    }

    /**
     * The conventional GETNEXT successor: this OID with a trailing {@code .0} appended. See the class
     * documentation for why this seeds a subtree walk. {@code x.next()} is always strictly greater
     * than {@code x} under {@link #compareTo}.
     */
    public Oid next() {
        return child(0L);
    }

    /**
     * SNMP lexicographic comparison: compare sub-identifiers left to right; the first difference
     * decides, and a strict prefix sorts before the longer OID.
     */
    @Override
    public int compareTo(Oid other) {
        int min = Math.min(ids.length, other.ids.length);
        for (int i = 0; i < min; i++) {
            int cmp = Long.compare(ids[i], other.ids[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(ids.length, other.ids.length);
    }

    /** The dotted form without a leading dot, e.g. {@code 1.3.6.1.2.1.1.1.0}; round-trips through {@link #parse}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(ids.length * 3);
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(ids[i]);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Oid other && Arrays.equals(ids, other.ids);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(ids);
    }
}
