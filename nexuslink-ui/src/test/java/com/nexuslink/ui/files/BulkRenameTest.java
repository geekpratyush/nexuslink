package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BulkRenameTest {

    private static BulkRename.Rule rule(String find, String replace, boolean regex,
                                        String prefix, String suffix,
                                        String token, int start, int step, int pad,
                                        BulkRename.Case c) {
        return new BulkRename.Rule(find, replace, regex, prefix, suffix, token, start, step, pad, c);
    }

    private static List<String> tos(List<BulkRename.Result> r) {
        return r.stream().map(BulkRename.Result::to).toList();
    }

    @Test
    void literalFindReplacePreservesExtension() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("draft report.txt", "draft notes.md"),
                rule("draft ", "final ", false, "", "", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("final report.txt", "final notes.md"), tos(r));
    }

    @Test
    void literalReplacementIsNotTreatedAsRegex() {
        // "$1" must land verbatim, not as a backreference, in literal mode.
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("a+b.txt"), rule("+", "$1", false, "", "", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("a$1b.txt"), tos(r));
    }

    @Test
    void regexBackreferencesWork() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("IMG_1234.jpg"),
                rule("IMG_(\\d+)", "photo-$1", true, "", "", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("photo-1234.jpg"), tos(r));
    }

    @Test
    void invalidRegexSkipsTheStepInsteadOfThrowing() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("keep.txt"), rule("(", "x", true, "", "", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("keep.txt"), tos(r));
    }

    @Test
    void prefixSuffixWrapTheBaseNotTheExtension() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("photo.jpg"),
                rule("", "", false, "2024_", "_edited", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("2024_photo_edited.jpg"), tos(r));
    }

    @Test
    void sequentialNumberingWithPadding() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("a.txt", "b.txt", "c.txt"),
                rule("", "", false, "file_", "{n}", "{n}", 1, 1, 3, BulkRename.Case.KEEP));
        assertEquals(List.of("file_a001.txt", "file_b002.txt", "file_c003.txt"), tos(r));
    }

    @Test
    void numberingHonoursStartAndStep() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("a", "b", "c"),
                rule("", "", false, "", "{n}", "{n}", 10, 5, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("a10", "b15", "c20"), tos(r));
    }

    @Test
    void caseTransformLeavesExtensionAlone() {
        List<BulkRename.Result> lower = BulkRename.preview(
                List.of("ReadMe.TXT"), rule("", "", false, "", "", "", 1, 1, 0, BulkRename.Case.LOWER));
        assertEquals(List.of("readme.TXT"), tos(lower));

        List<BulkRename.Result> upper = BulkRename.preview(
                List.of("ReadMe.txt"), rule("", "", false, "", "", "", 1, 1, 0, BulkRename.Case.UPPER));
        assertEquals(List.of("README.txt"), tos(upper));
    }

    @Test
    void dotfilesHaveNoExtensionSplit() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of(".bashrc"), rule("", "", false, "", "_bak", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of(".bashrc_bak"), tos(r));
    }

    @Test
    void filesWithoutExtensionAreHandled() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("Makefile"), rule("", "", false, "", "", "", 1, 1, 0, BulkRename.Case.LOWER));
        assertEquals(List.of("makefile"), tos(r));
    }

    @Test
    void collidingTargetsAreFlaggedOnBothRows() {
        // Stripping the digit makes both collapse to "file.txt".
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("file1.txt", "file2.txt"),
                rule("\\d", "", true, "", "", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertEquals(List.of("file.txt", "file.txt"), tos(r));
        assertTrue(r.get(0).collision());
        assertTrue(r.get(1).collision());
    }

    @Test
    void distinctTargetsAreNotFlagged() {
        List<BulkRename.Result> r = BulkRename.preview(
                List.of("a.txt", "b.txt"),
                rule("", "", false, "x_", "", "", 1, 1, 0, BulkRename.Case.KEEP));
        assertFalse(r.get(0).collision());
        assertFalse(r.get(1).collision());
    }
}
