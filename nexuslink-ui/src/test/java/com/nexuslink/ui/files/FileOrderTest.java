package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileOrderTest {

    private static FileItem file(String name) {
        return FileItem.of(name, "/" + name, false, 1, "", "");
    }

    private static FileItem file(String name, long size, String modified, String perms) {
        return FileItem.of(name, "/" + name, false, size, modified, perms);
    }

    private static FileItem dir(String name) {
        return FileItem.of(name, "/" + name, true, 0, "", "");
    }

    private static List<String> names(List<FileItem> items) {
        return items.stream().map(FileItem::name).toList();
    }

    @Test
    void parentRowSortsFirst() {
        List<FileItem> sorted = FileOrder.sorted(List.of(file("a"), dir("z"), FileItem.up("/")));
        assertEquals("..", sorted.get(0).name());
    }

    @Test
    void directoriesComeBeforeFiles() {
        List<FileItem> sorted = FileOrder.sorted(List.of(file("alpha"), dir("zeta"), file("beta"), dir("mu")));
        assertEquals(List.of("mu", "zeta", "alpha", "beta"), names(sorted));
    }

    @Test
    void namesAreSortedCaseInsensitively() {
        List<FileItem> sorted = FileOrder.sorted(List.of(file("Banana"), file("apple"), file("Cherry")));
        assertEquals(List.of("apple", "Banana", "Cherry"), names(sorted));
    }

    @Test
    void fullOrderingParentThenDirsThenFiles() {
        List<FileItem> sorted = FileOrder.sorted(List.of(
                file("readme.txt"), dir("src"), FileItem.up("/"), dir("Docs"), file("Build.gradle")));
        assertEquals(List.of("..", "Docs", "src", "Build.gradle", "readme.txt"), names(sorted));
    }

    @Test
    void inputListIsNotMutated() {
        List<FileItem> input = List.of(file("b"), file("a"));
        FileOrder.sorted(input);
        assertEquals(List.of("b", "a"), names(input));
    }

    @Test
    void sortBySizeAscendingKeepsParentAndDirsFirst() {
        List<FileItem> sorted = FileOrder.sorted(List.of(
                        file("big", 3000, "", ""), dir("z"), FileItem.up("/"),
                        file("small", 10, "", ""), dir("a")),
                FileOrder.SortKey.SIZE, true);
        // ".." first, then dirs (still name-sorted among themselves), then files by ascending size.
        assertEquals(List.of("..", "a", "z", "small", "big"), names(sorted));
    }

    @Test
    void descendingFlipsOnlyTheKeyNotTheGrouping() {
        List<FileItem> sorted = FileOrder.sorted(List.of(
                        file("small", 10, "", ""), dir("d"), FileItem.up("/"),
                        file("big", 3000, "", "")),
                FileOrder.SortKey.SIZE, false);
        // ".." and the directory still lead; only the files reverse to size-descending.
        assertEquals(List.of("..", "d", "big", "small"), names(sorted));
    }

    @Test
    void sortByModifiedThenTiesBreakByName() {
        List<FileItem> sorted = FileOrder.sorted(List.of(
                        file("b", 1, "2026-01-01", ""), file("a", 1, "2026-01-01", ""),
                        file("c", 1, "2025-06-01", "")),
                FileOrder.SortKey.MODIFIED, true);
        assertEquals(List.of("c", "a", "b"), names(sorted));
    }

    @Test
    void nameDescendingReversesFilesButParentStaysFirst() {
        List<FileItem> sorted = FileOrder.sorted(List.of(
                        file("apple"), file("cherry"), FileItem.up("/"), file("banana")),
                FileOrder.SortKey.NAME, false);
        assertEquals(List.of("..", "cherry", "banana", "apple"), names(sorted));
    }
}
