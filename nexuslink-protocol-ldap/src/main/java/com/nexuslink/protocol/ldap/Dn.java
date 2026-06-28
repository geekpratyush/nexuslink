package com.nexuslink.protocol.ldap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A Distinguished Name (DN): an ordered, most-specific-first sequence of {@link Rdn} components
 * (RFC 4514). Parsing handles escaped separators; equality is name-normalized (types lower-cased,
 * insignificant whitespace removed, multi-valued RDN ordering ignored).
 */
public final class Dn {

    /** The empty DN (root of the directory tree). */
    public static final Dn EMPTY = new Dn(List.of());

    private final List<Rdn> rdns;

    private Dn(List<Rdn> rdns) {
        this.rdns = List.copyOf(rdns);
    }

    /** Parse a DN string such as {@code "cn=John Smith,ou=People,dc=example,dc=com"}. */
    public static Dn parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            return EMPTY;
        }
        List<Rdn> parsed = new ArrayList<>();
        for (String part : Rdn.splitUnescaped(text, ',')) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Empty RDN in DN: " + text);
            }
            parsed.add(Rdn.parse(part));
        }
        return new Dn(parsed);
    }

    /** Build a DN from RDNs, most specific first. */
    public static Dn of(List<Rdn> rdns) {
        return rdns.isEmpty() ? EMPTY : new Dn(rdns);
    }

    /** The RDN components, most specific (leaf) first. */
    public List<Rdn> rdns() {
        return rdns;
    }

    /** True if this is the empty (root) DN. */
    public boolean isEmpty() {
        return rdns.isEmpty();
    }

    /** Number of RDN components. */
    public int size() {
        return rdns.size();
    }

    /** The leaf (most specific) RDN, or empty for the root DN. */
    public Rdn rdn() {
        return rdns.isEmpty() ? null : rdns.get(0);
    }

    /** The parent DN (this DN minus its leaf RDN); {@link #EMPTY} when at or one above the root. */
    public Dn parent() {
        if (rdns.size() <= 1) {
            return EMPTY;
        }
        return new Dn(rdns.subList(1, rdns.size()));
    }

    /** A child DN formed by prepending {@code rdn} as the new leaf. */
    public Dn child(Rdn rdn) {
        Objects.requireNonNull(rdn, "rdn");
        List<Rdn> next = new ArrayList<>(rdns.size() + 1);
        next.add(rdn);
        next.addAll(rdns);
        return new Dn(next);
    }

    /** True if {@code other} is an ancestor of (or equal to) this DN. */
    public boolean isDescendantOf(Dn other) {
        Objects.requireNonNull(other, "other");
        if (other.size() > size()) {
            return false;
        }
        int offset = size() - other.size();
        for (int i = 0; i < other.size(); i++) {
            if (!rdns.get(offset + i).equals(other.rdns.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Canonical render: RDNs joined with {@code ','}, values escaped per RFC 4514. */
    @Override
    public String toString() {
        return rdns.stream().map(Rdn::toString).collect(Collectors.joining(","));
    }

    private String normalized() {
        return rdns.stream().map(Rdn::normalized).collect(Collectors.joining(","));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dn other && normalized().equals(other.normalized());
    }

    @Override
    public int hashCode() {
        return normalized().hashCode();
    }
}
