package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileFilterTest {

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
    void hidesDotfilesByDefault() {
        List<FileItem> out = FileFilter.apply(
                List.of(file(".bashrc"), file("readme"), dir(".git"), dir("src")), false, "");
        assertEquals(List.of("readme", "src"), names(out));
    }

    @Test
    void showHiddenRevealsDotfiles() {
        List<FileItem> out = FileFilter.apply(
                List.of(file(".bashrc"), file("readme")), true, "");
        assertEquals(List.of(".bashrc", "readme"), names(out));
    }

    @Test
    void parentRowSurvivesEvenHiddenAndFiltered() {
        FileItem up = FileItem.up("/");
        assertTrue(FileFilter.accept(up, false, "zzz"));
        List<FileItem> out = FileFilter.apply(List.of(up, file("apple")), false, "zzz");
        assertEquals(List.of(".."), names(out));
    }

    @Test
    void quickFilterMatchesNameCaseInsensitively() {
        List<FileItem> out = FileFilter.apply(
                List.of(file("Report.pdf"), file("notes.txt"), dir("reports")), false, "rep");
        assertEquals(List.of("Report.pdf", "reports"), names(out));
    }

    @Test
    void blankOrNullQueryMatchesAll() {
        List<FileItem> items = List.of(file("a"), file("b"));
        assertEquals(2, FileFilter.apply(items, false, "").size());
        assertEquals(2, FileFilter.apply(items, false, "   ").size());
        assertEquals(2, FileFilter.apply(items, false, null).size());
    }

    @Test
    void hiddenAndQueryCombine() {
        List<FileItem> out = FileFilter.apply(
                List.of(file(".config"), file("config.yml"), file("other")), true, "config");
        assertEquals(List.of(".config", "config.yml"), names(out));
    }
}
