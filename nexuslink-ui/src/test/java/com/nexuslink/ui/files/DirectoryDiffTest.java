package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nexuslink.ui.files.DirectoryDiff.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryDiffTest {

    private static FileItem file(String name, long size, String modified) {
        return FileItem.of(name, "/" + name, false, size, modified, "rw-r--r--");
    }

    private static FileItem dir(String name) {
        return FileItem.of(name, "/" + name, true, 0, "", "rwxr-xr-x");
    }

    private static DirectoryDiff.Entry find(List<DirectoryDiff.Entry> diff, String name) {
        return diff.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void classifiesLeftOnlyRightOnlyAndSame() {
        List<DirectoryDiff.Entry> diff = DirectoryDiff.compare(
                List.of(file("shared.txt", 10, "Jan 1"), file("localonly.txt", 5, "Jan 1")),
                List.of(file("shared.txt", 10, "Jan 1"), file("remoteonly.txt", 8, "Jan 2")));

        assertEquals(SAME, find(diff, "shared.txt").status());
        assertEquals(LEFT_ONLY, find(diff, "localonly.txt").status());
        assertEquals(RIGHT_ONLY, find(diff, "remoteonly.txt").status());
    }

    @Test
    void differsWhenSizeOrModifiedDiffers() {
        List<DirectoryDiff.Entry> bySize = DirectoryDiff.compare(
                List.of(file("a", 10, "Jan 1")), List.of(file("a", 20, "Jan 1")));
        assertEquals(DIFFERENT, find(bySize, "a").status());

        List<DirectoryDiff.Entry> byTime = DirectoryDiff.compare(
                List.of(file("a", 10, "Jan 1")), List.of(file("a", 10, "Feb 9")));
        assertEquals(DIFFERENT, find(byTime, "a").status());
    }

    @Test
    void leftOnlyAndRightOnlyCarryOnlyTheirSide() {
        DirectoryDiff.Entry left = find(
                DirectoryDiff.compare(List.of(file("x", 1, "t")), List.of()), "x");
        assertEquals(LEFT_ONLY, left.status());
        assertNotNull(left.left());
        assertNull(left.right());

        DirectoryDiff.Entry right = find(
                DirectoryDiff.compare(List.of(), List.of(file("y", 1, "t"))), "y");
        assertEquals(RIGHT_ONLY, right.status());
        assertNull(right.left());
        assertNotNull(right.right());
    }

    @Test
    void sameNamedDirectoriesAreSameButDirVsFileDiffers() {
        assertEquals(SAME, find(
                DirectoryDiff.compare(List.of(dir("src")), List.of(dir("src"))), "src").status());

        // A directory on one side and a file with the same name on the other is a type mismatch.
        assertEquals(DIFFERENT, find(
                DirectoryDiff.compare(List.of(dir("data")), List.of(file("data", 3, "t"))), "data").status());
    }

    @Test
    void ignoresTheParentRowOnBothSides() {
        List<DirectoryDiff.Entry> diff = DirectoryDiff.compare(
                List.of(FileItem.up("/"), file("a", 1, "t")),
                List.of(FileItem.up("/"), file("a", 1, "t")));
        assertEquals(1, diff.size());
        assertEquals("a", diff.get(0).name());
    }

    @Test
    void caseInsensitiveMatchingCollapsesNamesThatDifferOnlyInCase() {
        // Case-sensitive (default): two distinct entries.
        List<DirectoryDiff.Entry> sensitive = DirectoryDiff.compare(
                List.of(file("README", 1, "t")), List.of(file("readme", 1, "t")));
        assertEquals(2, sensitive.size());
        assertEquals(LEFT_ONLY, find(sensitive, "README").status());
        assertEquals(RIGHT_ONLY, find(sensitive, "readme").status());

        // Case-insensitive: matched as one entry.
        List<DirectoryDiff.Entry> insensitive = DirectoryDiff.compare(
                List.of(file("README", 1, "t")), List.of(file("readme", 1, "t")), false);
        assertEquals(1, insensitive.size());
        assertEquals(SAME, insensitive.get(0).status());
    }

    @Test
    void resultIsOrderedDirectoriesFirstThenName() {
        List<DirectoryDiff.Entry> diff = DirectoryDiff.compare(
                List.of(file("zeta", 1, "t"), dir("beta"), file("alpha", 1, "t")),
                List.of(dir("gamma")));
        assertEquals(List.of("beta", "gamma", "alpha", "zeta"),
                diff.stream().map(DirectoryDiff.Entry::name).toList());
    }

    @Test
    void summaryCountsEveryStatus() {
        List<DirectoryDiff.Entry> diff = DirectoryDiff.compare(
                List.of(file("same", 1, "t"), file("diff", 1, "t"), file("lonly", 1, "t")),
                List.of(file("same", 1, "t"), file("diff", 2, "t"), file("ronly", 1, "t")));
        Map<DirectoryDiff.Status, Integer> s = DirectoryDiff.summary(diff);
        assertEquals(1, s.get(SAME));
        assertEquals(1, s.get(DIFFERENT));
        assertEquals(1, s.get(LEFT_ONLY));
        assertEquals(1, s.get(RIGHT_ONLY));
    }

    @Test
    void emptyOnBothSidesIsEmptyDiff() {
        assertTrue(DirectoryDiff.compare(List.of(), List.of()).isEmpty());
    }
}
