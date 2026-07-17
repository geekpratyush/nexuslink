package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the same-side move planner over a POSIX-style ("/"-separated) file system, so path semantics —
 * parent, join, subtree — are exercised without depending on the host OS separator.
 */
class SameSideMoveTest {

    /** A minimal "/"-separated file system: only the path methods the planner touches are real. */
    private static final class PosixFs implements FileSystem {
        @Override public String name() { return "Posix"; }
        @Override public String home() { return "/"; }
        @Override public String parent(String path) {
            if (path.equals("/")) return "/";
            String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            int slash = p.lastIndexOf('/');
            return slash <= 0 ? "/" : p.substring(0, slash);
        }
        @Override public String join(String dir, String name) {
            return dir.endsWith("/") ? dir + name : dir + "/" + name;
        }
        @Override public List<FileItem> list(String path) { return List.of(); }
        @Override public void mkdir(String path) {}
        @Override public void rename(String from, String to) {}
        @Override public void delete(FileItem item) {}
    }

    private static final FileSystem FS = new PosixFs();

    private static FileItem file(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return FileItem.of(name, path, false, 10, "", "");
    }

    private static FileItem dir(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return FileItem.of(name, path, true, 0, "", "");
    }

    @Test
    void planComputesTheJoinedDestinationForEachItem() {
        List<SameSideMove.Move> moves = SameSideMove.plan(
                List.of(file("/home/a.txt"), file("/home/b.txt")), "/home/sub", FS);

        assertEquals(2, moves.size());
        assertEquals("/home/a.txt", moves.get(0).from());
        assertEquals("/home/sub/a.txt", moves.get(0).to());
        assertEquals("/home/sub/b.txt", moves.get(1).to());
    }

    @Test
    void skipsAnItemAlreadyInTheDestinationDirectory() {
        // Its parent already is destDir → moving it there is a no-op.
        assertTrue(SameSideMove.plan(List.of(file("/home/a.txt")), "/home", FS).isEmpty());
        assertFalse(SameSideMove.isMovable(file("/home/a.txt"), "/home", FS));
    }

    @Test
    void skipsTheParentRow() {
        assertTrue(SameSideMove.plan(List.of(FileItem.up("/home")), "/home/sub", FS).isEmpty());
    }

    @Test
    void refusesToMoveADirectoryIntoItself() {
        assertFalse(SameSideMove.isMovable(dir("/home/docs"), "/home/docs", FS));
        assertTrue(SameSideMove.plan(List.of(dir("/home/docs")), "/home/docs", FS).isEmpty());
    }

    @Test
    void refusesToMoveADirectoryIntoItsOwnSubtree() {
        // /home/docs → /home/docs/archive would detach the tree from itself.
        assertFalse(SameSideMove.isMovable(dir("/home/docs"), "/home/docs/archive", FS));
        assertTrue(SameSideMove.plan(List.of(dir("/home/docs")), "/home/docs/archive", FS).isEmpty());
    }

    @Test
    void allowsMovingADirectoryIntoAnUnrelatedFolder() {
        // A sibling whose path merely shares a prefix string ("/home/docs2") is NOT under "/home/docs".
        assertTrue(SameSideMove.isMovable(dir("/home/docs"), "/home/docs2", FS));
        List<SameSideMove.Move> moves = SameSideMove.plan(List.of(dir("/home/docs")), "/home/docs2", FS);
        assertEquals("/home/docs2/docs", moves.get(0).to());
    }

    @Test
    void allowsMovingAFileDeeperIntoItsOwnDirectorysSubfolder() {
        // A file (not a directory) has no subtree, so moving it into a nested folder is always fine.
        assertTrue(SameSideMove.isMovable(file("/home/a.txt"), "/home/sub/deep", FS));
    }

    @Test
    void planKeepsOnlyTheLegalMovesFromaMixedSelection() {
        List<SameSideMove.Move> moves = SameSideMove.plan(List.of(
                file("/home/a.txt"),          // ok
                file("/home/sub/b.txt"),      // already in destDir → skip
                dir("/home/sub"),             // dir into itself → skip
                dir("/home/other")),          // ok
                "/home/sub", FS);

        assertEquals(List.of("/home/sub/a.txt", "/home/sub/other"),
                moves.stream().map(SameSideMove.Move::to).toList());
    }
}
