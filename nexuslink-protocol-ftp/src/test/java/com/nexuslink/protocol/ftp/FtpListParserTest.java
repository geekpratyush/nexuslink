package com.nexuslink.protocol.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FtpListParserTest {

    @Test
    void unixFileLineFieldsCorrect() {
        FtpListEntry e = FtpListParser.parseUnix(
                "-rw-r--r--   1 owner group    4096 Sep 12 14:22 file.txt").orElseThrow();
        assertEquals(FtpListEntry.Type.FILE, e.type());
        assertFalse(e.isDirectory());
        assertEquals("file.txt", e.name());
        assertEquals(4096, e.size());
        assertEquals("-rw-r--r--", e.permissions());
        assertEquals("Sep 12 14:22", e.dateText());
        assertEquals(null, e.linkTarget());
        assertFalse(e.isCurrentOrParent());
    }

    @Test
    void unixDirLineFieldsCorrect() {
        FtpListEntry e = FtpListParser.parseUnix(
                "drwxr-xr-x   2 owner group    4096 Jan  3  2023 subdir").orElseThrow();
        assertEquals(FtpListEntry.Type.DIR, e.type());
        assertTrue(e.isDirectory());
        assertEquals("subdir", e.name());
        assertEquals(4096, e.size());
        assertEquals("drwxr-xr-x", e.permissions());
        assertEquals("Jan 3 2023", e.dateText());
    }

    @Test
    void unixNameWithSpaces() {
        FtpListEntry e = FtpListParser.parseUnix(
                "-rw-r--r--   1 owner group     123 Sep 12 14:22 my report final.pdf").orElseThrow();
        assertEquals("my report final.pdf", e.name());
        assertEquals(123, e.size());
    }

    @Test
    void unixSymlinkSplitsTarget() {
        FtpListEntry e = FtpListParser.parseUnix(
                "lrwxrwxrwx   1 owner group      10 Sep 12 14:22 link -> /path/to/target").orElseThrow();
        assertEquals(FtpListEntry.Type.SYMLINK, e.type());
        assertTrue(e.isSymlink());
        assertEquals("link", e.name());
        assertEquals("/path/to/target", e.linkTarget());
    }

    @Test
    void unixYearInsteadOfTime() {
        FtpListEntry e = FtpListParser.parseUnix(
                "-rw-r--r--   1 owner group    4096 Jan  3  2023 old.txt").orElseThrow();
        assertEquals("Jan 3 2023", e.dateText());
        assertEquals("old.txt", e.name());
    }

    @Test
    void mlsdFileLine() {
        FtpListEntry e = FtpListParser.parseMlsd(
                "type=file;size=4096;modify=20230912142200; file.txt").orElseThrow();
        assertEquals(FtpListEntry.Type.FILE, e.type());
        assertEquals("file.txt", e.name());
        assertEquals(4096, e.size());
        assertEquals("20230912142200", e.modify());
        Optional<LocalDateTime> when = e.modifiedDateTime();
        assertTrue(when.isPresent());
        assertEquals(LocalDateTime.of(2023, 9, 12, 14, 22, 0), when.get());
        assertEquals("4096", e.facts().get("size"));
    }

    @Test
    void mlsdDirLine() {
        FtpListEntry e = FtpListParser.parseMlsd(
                "type=dir;modify=20230103000000; subdir").orElseThrow();
        assertEquals(FtpListEntry.Type.DIR, e.type());
        assertTrue(e.isDirectory());
        assertEquals("subdir", e.name());
        assertEquals(-1, e.size());
        assertEquals("20230103000000", e.modify());
    }

    @Test
    void mlsdFactNamesCaseInsensitive() {
        FtpListEntry e = FtpListParser.parseMlsd(
                "Type=File;Size=7;Modify=20240101010101; readme").orElseThrow();
        assertEquals(FtpListEntry.Type.FILE, e.type());
        assertEquals(7, e.size());
        assertEquals("20240101010101", e.modify());
    }

    @Test
    void autoDetectPicksUnix() {
        FtpListEntry e = FtpListParser.parseLine(
                "-rw-r--r--   1 owner group    4096 Sep 12 14:22 file.txt").orElseThrow();
        assertEquals(FtpListEntry.Type.FILE, e.type());
        assertEquals("-rw-r--r--", e.permissions());
        assertEquals(null, e.modify());
    }

    @Test
    void autoDetectPicksMlsd() {
        FtpListEntry e = FtpListParser.parseLine(
                "type=file;size=4096;modify=20230912142200; file.txt").orElseThrow();
        assertEquals(FtpListEntry.Type.FILE, e.type());
        assertEquals("20230912142200", e.modify());
        assertEquals(null, e.permissions());
    }

    @Test
    void garbageLineReturnsEmpty() {
        assertTrue(FtpListParser.parseLine("this is not a listing line").isEmpty());
        assertTrue(FtpListParser.parseUnix("this is not a listing line").isEmpty());
        assertTrue(FtpListParser.parseMlsd("no-facts-here").isEmpty());
        assertTrue(FtpListParser.parseLine(null).isEmpty());
    }

    @Test
    void mlsdCurrentAndParentFlagged() {
        FtpListEntry cdir = FtpListParser.parseMlsd("type=cdir;modify=20230101000000; .").orElseThrow();
        FtpListEntry pdir = FtpListParser.parseMlsd("type=pdir;modify=20230101000000; ..").orElseThrow();
        assertEquals(FtpListEntry.Type.CDIR, cdir.type());
        assertTrue(cdir.isCurrentOrParent());
        assertEquals(FtpListEntry.Type.PDIR, pdir.type());
        assertTrue(pdir.isCurrentOrParent());
    }

    @Test
    void bulkParseSkipsBlankGarbageAndDotEntries() {
        String listing = String.join("\n",
                "type=cdir;modify=20230101000000; .",
                "type=pdir;modify=20230101000000; ..",
                "",
                "garbage line without structure",
                "type=file;size=10;modify=20230101000000; a.txt",
                "-rw-r--r--   1 owner group    20 Sep 12 14:22 b.txt");
        List<FtpListEntry> entries = FtpListParser.parse(listing);
        assertEquals(2, entries.size());
        assertEquals("a.txt", entries.get(0).name());
        assertEquals("b.txt", entries.get(1).name());
    }

    @Test
    void bulkParseFromList() {
        List<FtpListEntry> entries = FtpListParser.parse(List.of(
                "drwxr-xr-x   2 owner group    4096 Jan  3  2023 subdir",
                "-rw-r--r--   1 owner group    4096 Sep 12 14:22 file.txt"));
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).isDirectory());
        assertEquals("file.txt", entries.get(1).name());
    }
}
