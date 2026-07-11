package com.nexuslink.ui.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class S3PathTest {

    @Test
    void rootDetection() {
        assertTrue(S3Path.isRoot("/"));
        assertTrue(S3Path.isRoot(""));
        assertTrue(S3Path.isRoot(null));
        assertFalse(S3Path.isRoot("/bucket"));
    }

    @Test
    void bucketExtraction() {
        assertNull(S3Path.bucket("/"));
        assertEquals("my-bucket", S3Path.bucket("/my-bucket"));
        assertEquals("my-bucket", S3Path.bucket("/my-bucket/a/b/"));
    }

    @Test
    void prefixExtraction() {
        assertNull(S3Path.prefix("/"));
        assertEquals("", S3Path.prefix("/my-bucket"));
        assertEquals("a/b/", S3Path.prefix("/my-bucket/a/b/"));
        assertEquals("a/file.txt", S3Path.prefix("/my-bucket/a/file.txt"));
    }

    @Test
    void parentNavigation() {
        assertEquals("/", S3Path.parent("/"));
        assertEquals("/", S3Path.parent("/my-bucket"));
        assertEquals("/my-bucket/", S3Path.parent("/my-bucket/a/"));
        assertEquals("/my-bucket/a/", S3Path.parent("/my-bucket/a/b/"));
        assertEquals("/my-bucket/a/", S3Path.parent("/my-bucket/a/file.txt"));
    }

    @Test
    void joinBucketAtRoot() {
        assertEquals("/my-bucket", S3Path.join("/", "my-bucket", false));
        assertEquals("/my-bucket", S3Path.join("/", "my-bucket", true)); // buckets have no trailing slash
    }

    @Test
    void joinInsideBucket() {
        assertEquals("/b/folder/", S3Path.join("/b", "folder", true));
        assertEquals("/b/file.txt", S3Path.join("/b", "file.txt", false));
        assertEquals("/b/a/sub/", S3Path.join("/b/a/", "sub", true));
        assertEquals("/b/a/f.txt", S3Path.join("/b/a/", "f.txt", false));
    }

    @Test
    void childKeyForUpload() {
        assertEquals("file.txt", S3Path.childKey("/bucket", "file.txt"));
        assertEquals("a/b/file.txt", S3Path.childKey("/bucket/a/b/", "file.txt"));
    }

    @Test
    void lastSegment() {
        assertEquals("c.txt", S3Path.lastSegment("a/b/c.txt"));
        assertEquals("b", S3Path.lastSegment("a/b/"));
        assertEquals("only", S3Path.lastSegment("only"));
        assertEquals("", S3Path.lastSegment(""));
    }

    @Test
    void joinTrimsStraySlashesInName() {
        assertEquals("/b/folder/", S3Path.join("/b", "/folder/", true));
    }
}
