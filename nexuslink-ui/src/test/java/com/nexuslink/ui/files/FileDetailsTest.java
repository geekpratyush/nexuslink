package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileDetailsTest {

    @Test
    void convertsCommonSymbolicPermissions() {
        assertEquals("755", FileDetails.permissionsOctal("rwxr-xr-x"));
        assertEquals("644", FileDetails.permissionsOctal("rw-r--r--"));
        assertEquals("777", FileDetails.permissionsOctal("rwxrwxrwx"));
        assertEquals("000", FileDetails.permissionsOctal("---------"));
    }

    @Test
    void stripsLeadingTypeCharacter() {
        assertEquals("755", FileDetails.permissionsOctal("drwxr-xr-x"));
        assertEquals("644", FileDetails.permissionsOctal("-rw-r--r--"));
    }

    @Test
    void setuidStickyCountAsExecuteBit() {
        assertEquals("755", FileDetails.permissionsOctal("rwsr-xr-x"));   // setuid → exec bit set
        assertEquals("644", FileDetails.permissionsOctal("rw-r--r-T"));   // T (no exec) → bit clear
    }

    @Test
    void rejectsMalformedStrings() {
        assertNull(FileDetails.permissionsOctal(null));
        assertNull(FileDetails.permissionsOctal("rwx"));
        assertNull(FileDetails.permissionsOctal("zwxr-xr-x"));
        assertNull(FileDetails.permissionsOctal("rwxr-xr-xx"));   // 10 chars but no valid type prefix form
    }

    @Test
    void buildsRowsForAFile() {
        FileItem f = FileItem.of("report.pdf", "/docs/report.pdf", false, 2048, "Jan 2 10:00", "rw-r--r--");
        List<FileDetails.Row> rows = FileDetails.of(f);
        assertEquals("report.pdf", value(rows, "Name"));
        assertEquals("File", value(rows, "Type"));
        assertEquals("/docs/report.pdf", value(rows, "Path"));
        assertTrue(value(rows, "Size").contains("2048 bytes"));
        assertEquals("Jan 2 10:00", value(rows, "Modified"));
        assertTrue(value(rows, "Permissions").contains("644"), value(rows, "Permissions"));
    }

    @Test
    void directoryHasNoSizeRow() {
        FileItem d = FileItem.of("src", "/src", true, 0, "", "rwxr-xr-x");
        List<FileDetails.Row> rows = FileDetails.of(d);
        assertEquals("Directory", value(rows, "Type"));
        assertNull(value(rows, "Size"));
    }

    private static String value(List<FileDetails.Row> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).map(FileDetails.Row::value).findFirst().orElse(null);
    }
}
