package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans a <em>same-side</em> move — dragging entries into a folder on the very same {@link FileSystem} —
 * as a set of {@link FileSystem#rename} operations rather than a copy-and-delete. Moving a file inside one
 * server (or one local disk) is just a rename of its path, so there is no reason to stream the bytes down
 * and back up; this decides <em>which</em> renames are legal and what each destination path is, leaving the
 * actual rename I/O and collision handling to the caller. Kept JavaFX-free and unit-testable, mirroring
 * {@link SyncPlanner} and {@link DuplicateName}.
 */
public final class SameSideMove {

    /** One planned rename: {@code item} moves from {@code from} to {@code to}. */
    public record Move(FileItem item, String from, String to) {}

    private SameSideMove() {}

    /**
     * The legal renames for dropping {@code items} into {@code destDir} on {@code fs}. An entry is dropped
     * from the plan when moving it would be a no-op or nonsensical:
     * <ul>
     *   <li>the synthetic ".." row;</li>
     *   <li>an entry already directly inside {@code destDir} (its parent is {@code destDir}) — nothing to do;</li>
     *   <li>a directory being dropped into itself or into its own subtree — which would detach it from the
     *       tree. This is detected via {@link FileSystem#parent} so it respects the file system's own path
     *       semantics rather than guessing at a separator.</li>
     * </ul>
     * Each surviving entry's destination is {@link FileSystem#join}({@code destDir}, name). Collisions with
     * an existing name in {@code destDir} are <em>not</em> resolved here (the planner does no I/O); the caller
     * checks {@link FileSystem#exists} at execution time.
     */
    public static List<Move> plan(List<FileItem> items, String destDir, FileSystem fs) {
        List<Move> moves = new ArrayList<>();
        for (FileItem item : items) {
            if (!isMovable(item, destDir, fs)) continue;
            moves.add(new Move(item, item.path(), fs.join(destDir, item.name())));
        }
        return moves;
    }

    /** Whether {@code item} can legally move into {@code destDir} (see {@link #plan} for the rules). */
    public static boolean isMovable(FileItem item, String destDir, FileSystem fs) {
        if (item == null || item.parent()) return false;
        if (fs.parent(item.path()).equals(destDir)) return false;          // already there — no-op
        if (item.directory() && isUnder(destDir, item.path(), fs)) return false;   // into itself/subtree
        return true;
    }

    /**
     * True if {@code path} is {@code ancestor} itself or lives somewhere beneath it, walking parents with
     * the file system's own {@link FileSystem#parent} (which returns a path unchanged once at the root).
     */
    private static boolean isUnder(String path, String ancestor, FileSystem fs) {
        String p = path;
        while (true) {
            if (p.equals(ancestor)) return true;
            String parent = fs.parent(p);
            if (parent.equals(p)) return false;   // reached the root without meeting the ancestor
            p = parent;
        }
    }
}
