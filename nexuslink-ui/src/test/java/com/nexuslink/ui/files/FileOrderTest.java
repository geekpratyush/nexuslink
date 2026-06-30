package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileOrderTest {

    private static FileItem file(String name) {
        return FileItem.of(name, "/" + name, false, 1, "", "");
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
}
