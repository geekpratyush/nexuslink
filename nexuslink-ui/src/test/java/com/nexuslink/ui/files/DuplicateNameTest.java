package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateNameTest {

    @Test
    void firstDuplicatePreservesExtension() {
        assertEquals("report copy.txt", DuplicateName.of("report.txt", Set.of()));
    }

    @Test
    void secondDuplicateAddsNumber() {
        assertEquals("report copy 2.txt", DuplicateName.of("report.txt", Set.of("report copy.txt")));
    }

    @Test
    void skipsRunOfExistingCopies() {
        assertEquals("report copy 3.txt",
                DuplicateName.of("report.txt", Set.of("report copy.txt", "report copy 2.txt")));
    }

    @Test
    void duplicatingACopyIncrementsRatherThanStacks() {
        assertEquals("report copy 2.txt", DuplicateName.of("report copy.txt", Set.of("report copy.txt")));
    }

    @Test
    void duplicatingANumberedCopyIncrementsTheNumber() {
        assertEquals("report copy 3.txt",
                DuplicateName.of("report copy 2.txt", Set.of("report copy 2.txt")));
    }

    @Test
    void extensionlessName() {
        assertEquals("Makefile copy", DuplicateName.of("Makefile", Set.of()));
    }

    @Test
    void dotfileIsTreatedAsHavingNoExtension() {
        assertEquals(".bashrc copy", DuplicateName.of(".bashrc", Set.of()));
    }

    @Test
    void multipleDotsUseOnlyTheLastAsExtension() {
        assertEquals("archive.tar copy.gz", DuplicateName.of("archive.tar.gz", Set.of()));
    }

    @Test
    void directoryNameWithNoExtensionGetsSuffix() {
        assertEquals("src copy", DuplicateName.of("src", Set.of()));
    }

    @Test
    void nameEndingInCopyWithoutSpaceIsNotTreatedAsACopy() {
        assertEquals("mycopy copy.txt", DuplicateName.of("mycopy.txt", Set.of()));
    }

    @Test
    void collectionOverloadMatchesPredicateOverload() {
        assertEquals("report copy 2.txt",
                DuplicateName.of("report.txt", List.of("report copy.txt")));
    }
}
