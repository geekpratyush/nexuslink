package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileItemTest {

    @Test
    void humanSizeFormatsUnits() {
        assertEquals("0 B", FileItem.humanSize(0));
        assertEquals("512 B", FileItem.humanSize(512));
        assertEquals("1023 B", FileItem.humanSize(1023));
        assertEquals("1.0 KB", FileItem.humanSize(1024));
        assertEquals("1.5 KB", FileItem.humanSize(1536));
        assertEquals("1.0 MB", FileItem.humanSize(1024L * 1024));
        assertEquals("1.0 GB", FileItem.humanSize(1024L * 1024 * 1024));
        assertEquals("1.0 TB", FileItem.humanSize(1024L * 1024 * 1024 * 1024));
    }

    @Test
    void sizeTextBlankForDirectories() {
        assertEquals("", FileItem.of("d", "/d", true, 0, "", "").sizeText());
        assertEquals("2.0 KB", FileItem.of("f", "/f", false, 2048, "", "").sizeText());
    }
}
