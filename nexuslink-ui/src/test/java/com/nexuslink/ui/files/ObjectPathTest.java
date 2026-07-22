package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObjectPathTest {

    @Test
    void rootDetection() {
        assertTrue(ObjectPath.isRoot("/"));
        assertTrue(ObjectPath.isRoot(""));
        assertTrue(ObjectPath.isRoot(null));
        assertFalse(ObjectPath.isRoot("/bucket"));
    }

    @Test
    void bucketExtraction() {
        assertNull(ObjectPath.bucket("/"));
        assertEquals("my-bucket", ObjectPath.bucket("/my-bucket"));
        assertEquals("my-bucket", ObjectPath.bucket("/my-bucket/a/b/"));
    }

    @Test
    void prefixExtraction() {
        assertNull(ObjectPath.prefix("/"));
        assertEquals("", ObjectPath.prefix("/my-bucket"));
        assertEquals("a/b/", ObjectPath.prefix("/my-bucket/a/b/"));
        assertEquals("a/file.txt", ObjectPath.prefix("/my-bucket/a/file.txt"));
    }

    @Test
    void parentNavigation() {
        assertEquals("/", ObjectPath.parent("/"));
        assertEquals("/", ObjectPath.parent("/my-bucket"));
        assertEquals("/my-bucket/", ObjectPath.parent("/my-bucket/a/"));
        assertEquals("/my-bucket/a/", ObjectPath.parent("/my-bucket/a/b/"));
        assertEquals("/my-bucket/a/", ObjectPath.parent("/my-bucket/a/file.txt"));
    }

    @Test
    void joinBucketAtRoot() {
        assertEquals("/my-bucket", ObjectPath.join("/", "my-bucket", false));
        assertEquals("/my-bucket", ObjectPath.join("/", "my-bucket", true)); // buckets have no trailing slash
    }

    @Test
    void joinInsideBucket() {
        assertEquals("/b/folder/", ObjectPath.join("/b", "folder", true));
        assertEquals("/b/file.txt", ObjectPath.join("/b", "file.txt", false));
        assertEquals("/b/a/sub/", ObjectPath.join("/b/a/", "sub", true));
        assertEquals("/b/a/f.txt", ObjectPath.join("/b/a/", "f.txt", false));
    }

    @Test
    void childKeyForUpload() {
        assertEquals("file.txt", ObjectPath.childKey("/bucket", "file.txt"));
        assertEquals("a/b/file.txt", ObjectPath.childKey("/bucket/a/b/", "file.txt"));
    }

    @Test
    void lastSegment() {
        assertEquals("c.txt", ObjectPath.lastSegment("a/b/c.txt"));
        assertEquals("b", ObjectPath.lastSegment("a/b/"));
        assertEquals("only", ObjectPath.lastSegment("only"));
        assertEquals("", ObjectPath.lastSegment(""));
    }

    @Test
    void joinTrimsStraySlashesInName() {
        assertEquals("/b/folder/", ObjectPath.join("/b", "/folder/", true));
    }
}
