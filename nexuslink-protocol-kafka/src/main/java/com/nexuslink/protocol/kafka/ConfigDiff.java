package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares a <em>desired</em> config against the <em>current</em> config for a Kafka
 * topic or broker and reports the per-key differences. Both sides are plain
 * {@code Map<String,String>} so this stays Kafka-type-free and fully pure/offline-
 * testable: callers read the live config into a map, build the map they want, and hand
 * both here. Each key is classified {@code ADDED} / {@code REMOVED} / {@code CHANGED} /
 * {@code UNCHANGED}. Keys can be marked <em>read-only</em> (non-updatable, such as broker
 * static configs); a change on such a key is still reported but flagged so a UI can warn
 * instead of attempting to apply it. Entries are sorted by key for stable, testable
 * output, and all results are immutable defensive copies.
 */
public final class ConfigDiff {

    /** How a single key differs between the desired and current configs. */
    public enum Change { ADDED, REMOVED, CHANGED, UNCHANGED }

    /**
     * One key's difference. For {@code ADDED} the {@code oldValue} is {@code null}; for
     * {@code REMOVED} the {@code newValue} is {@code null}. {@code readOnly} flags keys the
     * caller declared non-updatable — a change there should be surfaced as a warning.
     */
    public record Entry(String key, String oldValue, String newValue, Change change, boolean readOnly) {

        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(change, "change");
        }

        /** True when this entry represents an actual difference (not {@code UNCHANGED}). */
        public boolean isChange() {
            return change != Change.UNCHANGED;
        }

        /** True when this entry is both an actual change and applicable (not read-only). */
        public boolean isApplicable() {
            return isChange() && !readOnly;
        }
    }

    private final List<Entry> entries;

    private ConfigDiff(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /** Compares {@code desired} against {@code current} with no read-only keys. */
    public static ConfigDiff compare(Map<String, String> desired, Map<String, String> current) {
        return compare(desired, current, Set.of());
    }

    /**
     * Compares {@code desired} against {@code current}, flagging any key in
     * {@code readOnlyKeys} as non-updatable. Null maps are treated as empty; a null
     * read-only set is treated as none. The inputs are defensively copied and never
     * retained.
     */
    public static ConfigDiff compare(Map<String, String> desired,
                                     Map<String, String> current,
                                     Set<String> readOnlyKeys) {
        Map<String, String> desiredCopy = desired == null ? Map.of() : new LinkedHashMap<>(desired);
        Map<String, String> currentCopy = current == null ? Map.of() : new LinkedHashMap<>(current);
        Set<String> readOnly = readOnlyKeys == null ? Set.of() : Set.copyOf(readOnlyKeys);

        // Union of all keys, sorted for deterministic output.
        Set<String> keys = new TreeSet<>();
        keys.addAll(desiredCopy.keySet());
        keys.addAll(currentCopy.keySet());

        List<Entry> entries = new ArrayList<>(keys.size());
        for (String key : keys) {
            boolean inDesired = desiredCopy.containsKey(key);
            boolean inCurrent = currentCopy.containsKey(key);
            String newValue = desiredCopy.get(key);
            String oldValue = currentCopy.get(key);

            Change change;
            if (inDesired && !inCurrent) {
                change = Change.ADDED;
            } else if (!inDesired && inCurrent) {
                change = Change.REMOVED;
            } else if (Objects.equals(oldValue, newValue)) {
                change = Change.UNCHANGED;
            } else {
                change = Change.CHANGED;
            }
            entries.add(new Entry(key, oldValue, newValue, change, readOnly.contains(key)));
        }
        return new ConfigDiff(entries);
    }

    /** Every key's classification, sorted by key. Immutable. */
    public List<Entry> entries() {
        return entries;
    }

    /** Only the entries of the given classification, in key order. Immutable. */
    public List<Entry> entries(Change change) {
        Objects.requireNonNull(change, "change");
        List<Entry> filtered = new ArrayList<>();
        for (Entry e : entries) {
            if (e.change() == change) filtered.add(e);
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * The actionable set: {@code ADDED}, {@code CHANGED} and {@code REMOVED} entries,
     * excluding {@code UNCHANGED}. Read-only changes are still included (flagged) so the
     * caller decides whether to warn or apply. In key order. Immutable.
     */
    public List<Entry> changesToApply() {
        List<Entry> changes = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isChange()) changes.add(e);
        }
        return Collections.unmodifiableList(changes);
    }

    /** The subset of {@link #changesToApply()} flagged read-only (report, don't apply). */
    public List<Entry> readOnlyChanges() {
        List<Entry> ro = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isChange() && e.readOnly()) ro.add(e);
        }
        return Collections.unmodifiableList(ro);
    }

    /** The subset of {@link #changesToApply()} that is safe to apply (not read-only). */
    public List<Entry> applicableChanges() {
        List<Entry> ok = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isApplicable()) ok.add(e);
        }
        return Collections.unmodifiableList(ok);
    }

    /** True when at least one key differs (any ADDED/REMOVED/CHANGED). */
    public boolean hasChanges() {
        for (Entry e : entries) {
            if (e.isChange()) return true;
        }
        return false;
    }
}
