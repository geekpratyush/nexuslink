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

    // ---- content (hash-based) change detection ----

    /** Digests keyed by the exact FileItem, like the dialog's off-FX hash map. */
    private static DirectoryDiff.Digests digests(Map<FileItem, String> m) {
        return m::get;
    }

    /** Same-named file on a specific side (distinct path, as the two real panes always have). */
    private static FileItem sided(String side, String name, long size, String modified) {
        return FileItem.of(name, "/" + side + "/" + name, false, size, modified, "rw-r--r--");
    }

    @Test
    void contentMatchTreatsSameBytesAsSameDespiteDifferingTimestamps() {
        // The cross-file-system case: Local renders "2026-07-17 09:30", SFTP renders "Jul 17 09:30".
        // Identical bytes must not read as changed just because the two sides format the stamp differently.
        FileItem l = sided("local", "report.csv", 120, "2026-07-17 09:30");
        FileItem r = sided("remote", "report.csv", 120, "Jul 17 09:30");
        var d = digests(Map.of(l, "abc123", r, "abc123"));

        assertEquals(DIFFERENT, find(DirectoryDiff.compare(List.of(l), List.of(r)), "report.csv").status());
        assertEquals(SAME, find(DirectoryDiff.compare(List.of(l), List.of(r), true,
                DirectoryDiff.Match.CONTENT, d), "report.csv").status());
    }

    @Test
    void contentMatchCatchesDifferentBytesBehindAnIdenticalTimestamp() {
        // The inverse: same size and same stamp, but the bytes differ — metadata says SAME, content says not.
        FileItem l = sided("local", "data.bin", 64, "Jan 1");
        FileItem r = sided("remote", "data.bin", 64, "Jan 1");
        var d = digests(Map.of(l, "aaaa", r, "bbbb"));

        assertEquals(SAME, find(DirectoryDiff.compare(List.of(l), List.of(r)), "data.bin").status());
        assertEquals(DIFFERENT, find(DirectoryDiff.compare(List.of(l), List.of(r), true,
                DirectoryDiff.Match.CONTENT, d), "data.bin").status());
    }

    @Test
    void contentMatchStillRejectsOnSizeWithoutConsultingDigests() {
        // Differing sizes differ whatever the bytes say — and must not need a digest to decide.
        FileItem l = file("a.txt", 10, "Jan 1");
        FileItem r = file("a.txt", 20, "Jan 1");
        DirectoryDiff.Digests exploding = item -> { throw new AssertionError("should not hash on a size mismatch"); };

        assertEquals(DIFFERENT, find(DirectoryDiff.compare(List.of(l), List.of(r), true,
                DirectoryDiff.Match.CONTENT, exploding), "a.txt").status());
    }

    @Test
    void contentMatchFallsBackToTheTimestampWhenADigestIsMissing() {
        // A side that cannot hash keeps the metadata verdict rather than being guessed at.
        FileItem l = sided("local", "a.txt", 10, "Jan 1");
        FileItem r = sided("remote", "a.txt", 10, "Jan 1");
        FileItem l2 = sided("local", "b.txt", 10, "Jan 1");
        FileItem r2 = sided("remote", "b.txt", 10, "Jan 2");

        var onlyLeft = digests(Map.of(l, "abc"));           // right has no digest
        assertEquals(SAME, find(DirectoryDiff.compare(List.of(l), List.of(r), true,
                DirectoryDiff.Match.CONTENT, onlyLeft), "a.txt").status());
        assertEquals(DIFFERENT, find(DirectoryDiff.compare(List.of(l2), List.of(r2), true,
                DirectoryDiff.Match.CONTENT, DirectoryDiff.Digests.NONE), "b.txt").status());
    }

    @Test
    void contentMatchComparesDigestsCaseInsensitively() {
        FileItem l = sided("local", "a.txt", 10, "Jan 1");
        FileItem r = sided("remote", "a.txt", 10, "Jan 2");
        var d = digests(Map.of(l, "ABC123", r, "abc123"));

        assertEquals(SAME, find(DirectoryDiff.compare(List.of(l), List.of(r), true,
                DirectoryDiff.Match.CONTENT, d), "a.txt").status());
    }

    @Test
    void needsDigestSelectsEverySameSizePairIncludingMetadataSameOnes() {
        FileItem sameSizeDiffStamp = file("ambiguous.txt", 10, "Jan 1");
        FileItem sameSizeDiffStampR = file("ambiguous.txt", 10, "Jan 2");
        List<DirectoryDiff.Entry> diff = DirectoryDiff.compare(
                List.of(sameSizeDiffStamp, file("sized.txt", 10, "Jan 1"),
                        file("looksSame.txt", 5, "Jan 1"), file("leftonly.txt", 1, "Jan 1"), dir("sub")),
                List.of(sameSizeDiffStampR, file("sized.txt", 99, "Jan 1"),
                        file("looksSame.txt", 5, "Jan 1"), dir("sub")));

        List<DirectoryDiff.Entry> need = DirectoryDiff.needsDigest(diff);

        // Both same-size pairs are worth hashing — even looksSame.txt, which metadata called SAME, because
        // two byte-different files can share a size and stamp and that is exactly what content mode is for.
        // A size mismatch (sized.txt) is already decided; left-only/dirs have nothing to compare against.
        assertEquals(List.of("ambiguous.txt", "looksSame.txt"),
                need.stream().map(DirectoryDiff.Entry::name).sorted().toList());
    }

    @Test
    void needsDigestSkipsPairsAlreadyDecidedBySize() {
        // Different sizes and one-sided entries are decidable without any hashing.
        List<DirectoryDiff.Entry> diff = DirectoryDiff.compare(
                List.of(file("resized.txt", 10, "Jan 1"), file("leftonly.txt", 1, "Jan 1")),
                List.of(file("resized.txt", 20, "Jan 1"), file("rightonly.txt", 1, "Jan 1")));
        assertTrue(DirectoryDiff.needsDigest(diff).isEmpty());
    }

    @Test
    void contentMatchCatchesDifferentBytesBehindAnIdenticalSizeAndStamp() {
        // The demo case: same size, same timestamp, different bytes. Metadata is fooled; content is not,
        // and needsDigest must surface the pair so it actually gets hashed.
        FileItem l = sided("local", "data.bin", 4, "Jan 1");
        FileItem r = sided("remote", "data.bin", 4, "Jan 1");
        List<DirectoryDiff.Entry> metadata = DirectoryDiff.compare(List.of(l), List.of(r));
        assertEquals(SAME, find(metadata, "data.bin").status());

        assertEquals(List.of("data.bin"),
                DirectoryDiff.needsDigest(metadata).stream().map(DirectoryDiff.Entry::name).toList());
        var d = digests(Map.of(l, "aaaa", r, "bbbb"));
        assertEquals(DIFFERENT, find(DirectoryDiff.compare(List.of(l), List.of(r), true,
                DirectoryDiff.Match.CONTENT, d), "data.bin").status());
    }
}
