package com.nexuslink.protocol.ldap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A directory entry: a distinguished name plus an ordered multimap of attribute name &rarr; values.
 * Attribute insertion order and per-attribute value order are preserved for faithful LDIF output;
 * equality is LDAP-aware (DN name-normalized, attribute names compared case-insensitively).
 *
 * <p>Values are held as UTF-8 text. Non-ASCII, control, or otherwise non-"safe" values are carried
 * transparently — LDIF serialization base64-encodes them as needed.
 */
public final class LdapEntry {

    private final Dn dn;
    private final Map<String, List<String>> attributes = new LinkedHashMap<>();

    public LdapEntry(String dn) {
        this(Dn.parse(dn));
    }

    public LdapEntry(Dn dn) {
        this.dn = Objects.requireNonNull(dn, "dn");
    }

    /** The entry's distinguished name. */
    public Dn dn() {
        return dn;
    }

    /** Append one or more values to an attribute (creating it if absent). Returns {@code this}. */
    public LdapEntry add(String name, String... values) {
        Objects.requireNonNull(name, "name");
        List<String> list = attributes.computeIfAbsent(name, k -> new ArrayList<>());
        for (String v : values) {
            list.add(Objects.requireNonNull(v, "value"));
        }
        return this;
    }

    /** The values of an attribute, or an empty list when absent. Lookup is case-insensitive. */
    public List<String> values(String name) {
        List<String> direct = attributes.get(name);
        if (direct != null) {
            return List.copyOf(direct);
        }
        for (var e : attributes.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return List.copyOf(e.getValue());
            }
        }
        return List.of();
    }

    /** Attribute names in insertion order. */
    public List<String> attributeNames() {
        return List.copyOf(attributes.keySet());
    }

    /** Live-order view of attributes (name &rarr; values), insertion-ordered. */
    public Map<String, List<String>> attributes() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        attributes.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }

    /** True if no attributes have been added. */
    public boolean isEmpty() {
        return attributes.isEmpty();
    }

    private Map<String, List<String>> caseFolded() {
        Map<String, List<String>> folded = new TreeMap<>();
        attributes.forEach((k, v) -> folded.put(k.toLowerCase(Locale.ROOT), v));
        return folded;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LdapEntry other
                && dn.equals(other.dn)
                && caseFolded().equals(other.caseFolded());
    }

    @Override
    public int hashCode() {
        return Objects.hash(dn, caseFolded());
    }

    @Override
    public String toString() {
        return "LdapEntry[dn=" + dn + ", attributes=" + attributes + "]";
    }
}
