package com.nexuslink.protocol.kafka;

import com.nexuslink.protocol.kafka.ConfigDiff.Change;
import com.nexuslink.protocol.kafka.ConfigDiff.Entry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConfigDiffTest {

    private static Entry byKey(List<Entry> entries, String key) {
        return entries.stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void addedKeyDetected() {
        ConfigDiff diff = ConfigDiff.compare(Map.of("retention.ms", "1000"), Map.of());
        Entry e = byKey(diff.entries(), "retention.ms");
        assertEquals(Change.ADDED, e.change());
        assertNull(e.oldValue());
        assertEquals("1000", e.newValue());
        assertTrue(e.isChange());
        assertTrue(diff.hasChanges());
    }

    @Test
    void removedKeyDetected() {
        ConfigDiff diff = ConfigDiff.compare(Map.of(), Map.of("cleanup.policy", "delete"));
        Entry e = byKey(diff.entries(), "cleanup.policy");
        assertEquals(Change.REMOVED, e.change());
        assertEquals("delete", e.oldValue());
        assertNull(e.newValue());
    }

    @Test
    void changedKeyDetected() {
        ConfigDiff diff = ConfigDiff.compare(
                Map.of("retention.ms", "2000"),
                Map.of("retention.ms", "1000"));
        Entry e = byKey(diff.entries(), "retention.ms");
        assertEquals(Change.CHANGED, e.change());
        assertEquals("1000", e.oldValue());
        assertEquals("2000", e.newValue());
    }

    @Test
    void unchangedKeyDetected() {
        ConfigDiff diff = ConfigDiff.compare(
                Map.of("cleanup.policy", "compact"),
                Map.of("cleanup.policy", "compact"));
        Entry e = byKey(diff.entries(), "cleanup.policy");
        assertEquals(Change.UNCHANGED, e.change());
        assertEquals("compact", e.oldValue());
        assertEquals("compact", e.newValue());
        assertFalse(e.isChange());
        assertFalse(diff.hasChanges());
    }

    @Test
    void allFourClassificationsInOneDiff() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("added.key", "new");
        desired.put("changed.key", "v2");
        desired.put("same.key", "eq");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("removed.key", "gone");
        current.put("changed.key", "v1");
        current.put("same.key", "eq");

        ConfigDiff diff = ConfigDiff.compare(desired, current);
        assertEquals(Change.ADDED, byKey(diff.entries(), "added.key").change());
        assertEquals(Change.REMOVED, byKey(diff.entries(), "removed.key").change());
        assertEquals(Change.CHANGED, byKey(diff.entries(), "changed.key").change());
        assertEquals(Change.UNCHANGED, byKey(diff.entries(), "same.key").change());
        assertEquals(4, diff.entries().size());
    }

    @Test
    void changesToApplyExcludesUnchanged() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("added.key", "new");
        desired.put("changed.key", "v2");
        desired.put("same.key", "eq");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("removed.key", "gone");
        current.put("changed.key", "v1");
        current.put("same.key", "eq");

        ConfigDiff diff = ConfigDiff.compare(desired, current);
        List<Entry> changes = diff.changesToApply();
        Set<String> keys = changes.stream().map(Entry::key).collect(Collectors.toSet());
        assertEquals(Set.of("added.key", "changed.key", "removed.key"), keys);
        assertTrue(changes.stream().noneMatch(e -> e.change() == Change.UNCHANGED));
    }

    @Test
    void bothMapsEmptyProducesNoEntriesAndNoChanges() {
        ConfigDiff diff = ConfigDiff.compare(Map.of(), Map.of());
        assertTrue(diff.entries().isEmpty());
        assertTrue(diff.changesToApply().isEmpty());
        assertFalse(diff.hasChanges());
    }

    @Test
    void nullMapsAreTreatedAsEmpty() {
        ConfigDiff diff = ConfigDiff.compare(null, null, null);
        assertTrue(diff.entries().isEmpty());
        assertFalse(diff.hasChanges());
    }

    @Test
    void identicalMapsHaveNoChanges() {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("a", "1");
        cfg.put("b", "2");
        cfg.put("c", "3");
        ConfigDiff diff = ConfigDiff.compare(cfg, cfg);
        assertFalse(diff.hasChanges());
        assertTrue(diff.changesToApply().isEmpty());
        assertEquals(3, diff.entries().size());
        assertTrue(diff.entries().stream().allMatch(e -> e.change() == Change.UNCHANGED));
    }

    @Test
    void readOnlyChangeIsFlaggedButStillReported() {
        ConfigDiff diff = ConfigDiff.compare(
                Map.of("broker.id", "2"),
                Map.of("broker.id", "1"),
                Set.of("broker.id"));
        Entry e = byKey(diff.entries(), "broker.id");
        assertEquals(Change.CHANGED, e.change());
        assertTrue(e.readOnly());
        assertFalse(e.isApplicable());
        // Still surfaced in the actionable set...
        assertEquals(1, diff.changesToApply().size());
        // ...but split out from the applicable subset.
        assertTrue(diff.applicableChanges().isEmpty());
        assertEquals(List.of("broker.id"),
                diff.readOnlyChanges().stream().map(Entry::key).collect(Collectors.toList()));
    }

    @Test
    void readOnlyFlagOnlyAppliesToListedKeys() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("ro.key", "x2");
        desired.put("rw.key", "y2");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("ro.key", "x1");
        current.put("rw.key", "y1");

        ConfigDiff diff = ConfigDiff.compare(desired, current, Set.of("ro.key"));
        assertTrue(byKey(diff.entries(), "ro.key").readOnly());
        assertFalse(byKey(diff.entries(), "rw.key").readOnly());
        assertEquals(Set.of("rw.key"),
                diff.applicableChanges().stream().map(Entry::key).collect(Collectors.toSet()));
        assertEquals(Set.of("ro.key"),
                diff.readOnlyChanges().stream().map(Entry::key).collect(Collectors.toSet()));
    }

    @Test
    void unchangedReadOnlyKeyIsNotReportedAsChange() {
        ConfigDiff diff = ConfigDiff.compare(
                Map.of("broker.id", "1"),
                Map.of("broker.id", "1"),
                Set.of("broker.id"));
        assertFalse(diff.hasChanges());
        assertTrue(diff.readOnlyChanges().isEmpty());
        assertTrue(byKey(diff.entries(), "broker.id").readOnly());
    }

    @Test
    void entriesSortedByKey() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("zeta", "1");
        desired.put("alpha", "1");
        desired.put("mu", "1");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("beta", "1");
        current.put("alpha", "1");

        List<String> keys = ConfigDiff.compare(desired, current).entries()
                .stream().map(Entry::key).collect(Collectors.toList());
        assertEquals(List.of("alpha", "beta", "mu", "zeta"), keys);
    }

    @Test
    void changesToApplyIsAlsoSortedByKey() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("zeta", "n");
        desired.put("alpha", "n");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("mu", "o");

        List<String> keys = ConfigDiff.compare(desired, current).changesToApply()
                .stream().map(Entry::key).collect(Collectors.toList());
        assertEquals(List.of("alpha", "mu", "zeta"), keys);
    }

    @Test
    void entriesByClassificationFilter() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("a", "new");
        desired.put("b", "v2");
        Map<String, String> current = new LinkedHashMap<>();
        current.put("b", "v1");
        current.put("c", "gone");

        ConfigDiff diff = ConfigDiff.compare(desired, current);
        assertEquals(List.of("a"),
                diff.entries(Change.ADDED).stream().map(Entry::key).collect(Collectors.toList()));
        assertEquals(List.of("b"),
                diff.entries(Change.CHANGED).stream().map(Entry::key).collect(Collectors.toList()));
        assertEquals(List.of("c"),
                diff.entries(Change.REMOVED).stream().map(Entry::key).collect(Collectors.toList()));
        assertTrue(diff.entries(Change.UNCHANGED).isEmpty());
    }

    @Test
    void resultListsAreImmutable() {
        ConfigDiff diff = ConfigDiff.compare(Map.of("a", "1"), Map.of());
        assertThrows(UnsupportedOperationException.class,
                () -> diff.entries().add(new Entry("x", null, "y", Change.ADDED, false)));
        assertThrows(UnsupportedOperationException.class, () -> diff.changesToApply().clear());
    }

    @Test
    void inputMapMutationDoesNotAffectResult() {
        Map<String, String> desired = new LinkedHashMap<>();
        desired.put("a", "1");
        ConfigDiff diff = ConfigDiff.compare(desired, Map.of());
        desired.put("b", "2");
        desired.remove("a");
        // Snapshot taken at compare time: still exactly one ADDED entry for "a".
        assertEquals(1, diff.entries().size());
        assertEquals("a", diff.entries().get(0).key());
    }
}
