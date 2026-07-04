package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link DirectoryDiff} into an ordered list of concrete {@link Action}s to reconcile two
 * panes — the planning core of {@code SyncService} (7.1). It decides only <em>what</em> to do (copy
 * which way, or delete which side); actually moving the bytes is left to {@link TransferQueue} and
 * {@link FileSystem#delete}. Kept JavaFX-free so the plan can be reviewed and unit-tested before a
 * single file is touched, mirroring {@link DirectoryDiff} and {@link FileOrder}.
 */
public final class SyncPlanner {

    /** The reconciliation strategy chosen by the user. */
    public enum Mode {
        /** Make the right side an exact copy of the left (copies over, and deletes right-only extras). */
        MIRROR_TO_RIGHT,
        /** Make the left side an exact copy of the right (copies over, and deletes left-only extras). */
        MIRROR_TO_LEFT,
        /** Copy new/changed files left → right without ever deleting (a safe "upload/contribute"). */
        UPDATE_RIGHT,
        /** Copy new/changed files right → left without ever deleting (a safe "download/contribute"). */
        UPDATE_LEFT
    }

    /** The primitive a planned {@link Action} performs. */
    public enum Op { COPY_LEFT_TO_RIGHT, COPY_RIGHT_TO_LEFT, DELETE_LEFT, DELETE_RIGHT }

    /**
     * One planned operation. {@code item} is the file to copy (its source side) or to delete;
     * {@code reason} carries the diff status that justified it, for display in a preview.
     */
    public record Action(String name, boolean directory, Op op, FileItem item, DirectoryDiff.Status reason) {}

    private SyncPlanner() {}

    /** Convenience: diff two listings (case-sensitive), then plan. */
    public static List<Action> plan(List<FileItem> left, List<FileItem> right, Mode mode) {
        return plan(DirectoryDiff.compare(left, right), mode);
    }

    /**
     * Builds the action list for {@code mode}, preserving the diff's directories-first, name order so
     * the plan reads predictably. SAME entries never yield an action; the two UPDATE_* modes never
     * yield a delete.
     */
    public static List<Action> plan(List<DirectoryDiff.Entry> diff, Mode mode) {
        List<Action> actions = new ArrayList<>();
        for (DirectoryDiff.Entry e : diff) {
            Action a = switch (mode) {
                case MIRROR_TO_RIGHT -> switch (e.status()) {
                    case LEFT_ONLY, DIFFERENT -> copy(e, Op.COPY_LEFT_TO_RIGHT, e.left());
                    case RIGHT_ONLY -> del(e, Op.DELETE_RIGHT, e.right());
                    case SAME -> null;
                };
                case MIRROR_TO_LEFT -> switch (e.status()) {
                    case RIGHT_ONLY, DIFFERENT -> copy(e, Op.COPY_RIGHT_TO_LEFT, e.right());
                    case LEFT_ONLY -> del(e, Op.DELETE_LEFT, e.left());
                    case SAME -> null;
                };
                case UPDATE_RIGHT -> switch (e.status()) {
                    case LEFT_ONLY, DIFFERENT -> copy(e, Op.COPY_LEFT_TO_RIGHT, e.left());
                    case RIGHT_ONLY, SAME -> null;
                };
                case UPDATE_LEFT -> switch (e.status()) {
                    case RIGHT_ONLY, DIFFERENT -> copy(e, Op.COPY_RIGHT_TO_LEFT, e.right());
                    case LEFT_ONLY, SAME -> null;
                };
            };
            if (a != null) actions.add(a);
        }
        return actions;
    }

    /** A count of each {@link Op} in a plan — handy for a "3 copy · 1 delete" preview summary. */
    public static Map<Op, Integer> summary(List<Action> plan) {
        Map<Op, Integer> counts = new LinkedHashMap<>();
        for (Op o : Op.values()) counts.put(o, 0);
        for (Action a : plan) counts.merge(a.op(), 1, Integer::sum);
        return counts;
    }

    private static Action copy(DirectoryDiff.Entry e, Op op, FileItem source) {
        return new Action(e.name(), e.directory(), op, source, e.status());
    }

    private static Action del(DirectoryDiff.Entry e, Op op, FileItem victim) {
        return new Action(e.name(), e.directory(), op, victim, e.status());
    }
}
