package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuickViewTest {

    private static FileItem file(String name, long size) {
        return FileItem.of(name, "/x/" + name, false, size, "", "");
    }

    @Test
    void classifiesTextByExtension() {
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("notes.txt", 10)));
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("config.yaml", 10)));
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("Main.java", 10)));
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("query.sql", 10)));
    }

    @Test
    void classifiesImageByExtension() {
        assertEquals(QuickView.Kind.IMAGE, QuickView.classify(file("logo.png", 10)));
        assertEquals(QuickView.Kind.IMAGE, QuickView.classify(file("photo.JPEG", 10)));
    }

    @Test
    void classifiesKnownExtensionlessNamesAsText() {
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("Dockerfile", 10)));
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("README", 10)));
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file(".gitignore", 10)));
    }

    @Test
    void unknownBinaryIsUnsupported() {
        assertEquals(QuickView.Kind.UNSUPPORTED, QuickView.classify(file("archive.zip", 10)));
        assertEquals(QuickView.Kind.UNSUPPORTED, QuickView.classify(file("app.bin", 10)));
        assertEquals(QuickView.Kind.UNSUPPORTED, QuickView.classify(file("noext", 10)));
    }

    @Test
    void oversizeFilesAreRejected() {
        assertEquals(QuickView.Kind.UNSUPPORTED, QuickView.classify(file("huge.txt", 5L * 1024 * 1024)));
        assertEquals(QuickView.Kind.TEXT, QuickView.classify(file("small.txt", 100), 1024));
        assertEquals(QuickView.Kind.UNSUPPORTED, QuickView.classify(file("big.txt", 2048), 1024));
    }

    @Test
    void directoriesAndParentRowAreUnsupported() {
        assertEquals(QuickView.Kind.UNSUPPORTED,
                QuickView.classify(FileItem.of("dir", "/dir", true, 0, "", "")));
        assertEquals(QuickView.Kind.UNSUPPORTED, QuickView.classify(FileItem.up("/")));
    }

    @Test
    void isEditableOnlyForText() {
        assertTrue(QuickView.isEditable(file("a.md", 10), QuickView.DEFAULT_MAX_BYTES));
        assertFalse(QuickView.isEditable(file("a.png", 10), QuickView.DEFAULT_MAX_BYTES));
    }

    @Test
    void extensionHelper() {
        assertEquals("txt", QuickView.extension("a.txt"));
        assertEquals("gz", QuickView.extension("a.tar.gz"));
        assertEquals("", QuickView.extension("noext"));
        assertEquals("", QuickView.extension(".gitignore")); // leading-dot dotfile → no ext
        assertEquals("json", QuickView.extension("/path/to/File.JSON".toLowerCase()));
    }
}
