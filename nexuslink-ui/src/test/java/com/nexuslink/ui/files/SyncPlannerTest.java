package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nexuslink.ui.files.SyncPlanner.Op.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncPlannerTest {

    private static FileItem file(String name, long size, String modified) {
        return FileItem.of(name, "/" + name, false, size, modified, "rw-r--r--");
    }

    // left has: same, changed, leftonly ; right has: same, changed(diff size), rightonly
    private static List<FileItem> left() {
        return List.of(file("same", 1, "t"), file("changed", 1, "t"), file("leftonly", 1, "t"));
    }

    private static List<FileItem> right() {
        return List.of(file("same", 1, "t"), file("changed", 2, "t"), file("rightonly", 1, "t"));
    }

    private static SyncPlanner.Action forName(List<SyncPlanner.Action> plan, String name) {
        return plan.stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    void mirrorToRightCopiesNewAndChangedAndDeletesRightExtras() {
        List<SyncPlanner.Action> plan = SyncPlanner.plan(left(), right(), SyncPlanner.Mode.MIRROR_TO_RIGHT);
        assertEquals(COPY_LEFT_TO_RIGHT, forName(plan, "leftonly").op());
        assertEquals(COPY_LEFT_TO_RIGHT, forName(plan, "changed").op());
        assertEquals(DELETE_RIGHT, forName(plan, "rightonly").op());
        assertNull(forName(plan, "same"), "identical files need no action");
        assertEquals(3, plan.size());
    }

    @Test
    void mirrorToLeftIsTheMirrorImage() {
        List<SyncPlanner.Action> plan = SyncPlanner.plan(left(), right(), SyncPlanner.Mode.MIRROR_TO_LEFT);
        assertEquals(COPY_RIGHT_TO_LEFT, forName(plan, "rightonly").op());
        assertEquals(COPY_RIGHT_TO_LEFT, forName(plan, "changed").op());
        assertEquals(DELETE_LEFT, forName(plan, "leftonly").op());
        assertNull(forName(plan, "same"));
        assertEquals(3, plan.size());
    }

    @Test
    void updateRightNeverDeletes() {
        List<SyncPlanner.Action> plan = SyncPlanner.plan(left(), right(), SyncPlanner.Mode.UPDATE_RIGHT);
        assertEquals(COPY_LEFT_TO_RIGHT, forName(plan, "leftonly").op());
        assertEquals(COPY_LEFT_TO_RIGHT, forName(plan, "changed").op());
        assertNull(forName(plan, "rightonly"), "update must not delete the right-only extra");
        assertNull(forName(plan, "same"));
        assertTrue(plan.stream().noneMatch(a -> a.op() == DELETE_LEFT || a.op() == DELETE_RIGHT));
        assertEquals(2, plan.size());
    }

    @Test
    void updateLeftNeverDeletes() {
        List<SyncPlanner.Action> plan = SyncPlanner.plan(left(), right(), SyncPlanner.Mode.UPDATE_LEFT);
        assertEquals(COPY_RIGHT_TO_LEFT, forName(plan, "rightonly").op());
        assertEquals(COPY_RIGHT_TO_LEFT, forName(plan, "changed").op());
        assertNull(forName(plan, "leftonly"));
        assertNull(forName(plan, "same"));
        assertTrue(plan.stream().noneMatch(a -> a.op() == DELETE_LEFT || a.op() == DELETE_RIGHT));
        assertEquals(2, plan.size());
    }

    @Test
    void copyActionCarriesTheSourceSideItem() {
        // For MIRROR_TO_RIGHT, the "changed" copy source is the LEFT file (size 1), not the right (size 2).
        SyncPlanner.Action a = forName(
                SyncPlanner.plan(left(), right(), SyncPlanner.Mode.MIRROR_TO_RIGHT), "changed");
        assertEquals(1, a.item().size());
        assertEquals(DirectoryDiff.Status.DIFFERENT, a.reason());
    }

    @Test
    void deleteActionCarriesTheVictimSideItem() {
        SyncPlanner.Action a = forName(
                SyncPlanner.plan(left(), right(), SyncPlanner.Mode.MIRROR_TO_RIGHT), "rightonly");
        assertEquals(DELETE_RIGHT, a.op());
        assertEquals("rightonly", a.item().name());
        assertEquals(DirectoryDiff.Status.RIGHT_ONLY, a.reason());
    }

    @Test
    void summaryCountsOps() {
        Map<SyncPlanner.Op, Integer> s = SyncPlanner.summary(
                SyncPlanner.plan(left(), right(), SyncPlanner.Mode.MIRROR_TO_RIGHT));
        assertEquals(2, s.get(COPY_LEFT_TO_RIGHT));
        assertEquals(1, s.get(DELETE_RIGHT));
        assertEquals(0, s.get(COPY_RIGHT_TO_LEFT));
        assertEquals(0, s.get(DELETE_LEFT));
    }

    @Test
    void identicalTreesYieldEmptyPlanInEveryMode() {
        for (SyncPlanner.Mode m : SyncPlanner.Mode.values()) {
            assertTrue(SyncPlanner.plan(left(), left(), m).isEmpty(), "mode " + m + " should be a no-op");
        }
    }
}
