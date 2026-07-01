package com.nexuslink.protocol.gcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GcsUriTest {

    @Test
    void gsSchemeWithObject() {
        GcsUri u = GcsUri.parse("gs://my-bucket/path/to/file.txt");
        assertEquals("my-bucket", u.bucket());
        assertEquals("path/to/file.txt", u.object());
        assertTrue(u.hasObject());
    }

    @Test
    void gsSchemeBucketOnly() {
        GcsUri u = GcsUri.parse("gs://my-bucket");
        assertEquals("my-bucket", u.bucket());
        assertEquals("", u.object());
        assertFalse(u.hasObject());
        assertEquals("gs://my-bucket", u.toGsUri());
    }

    @Test
    void pathStyleApiHost() {
        GcsUri u = GcsUri.parse("https://storage.googleapis.com/my-bucket/a/b.json");
        assertEquals("my-bucket", u.bucket());
        assertEquals("a/b.json", u.object());
    }

    @Test
    void consoleHost() {
        GcsUri u = GcsUri.parse("https://storage.cloud.google.com/my-bucket/obj");
        assertEquals("my-bucket", u.bucket());
        assertEquals("obj", u.object());
    }

    @Test
    void virtualHostedStyle() {
        GcsUri u = GcsUri.parse("https://my-bucket.storage.googleapis.com/dir/file.bin");
        assertEquals("my-bucket", u.bucket());
        assertEquals("dir/file.bin", u.object());
    }

    @Test
    void objectIsUrlDecoded() {
        GcsUri u = GcsUri.parse("https://storage.googleapis.com/b/hello%20world%2Fx.txt");
        assertEquals("hello world/x.txt", u.object());
    }

    @Test
    void canonicalRoundTrip() {
        assertEquals("gs://b/k/x", GcsUri.parse("https://storage.googleapis.com/b/k/x").toGsUri());
        assertEquals("gs://b/k", GcsUri.parse("gs://b/k").toString());
    }

    @Test
    void malformedThrows() {
        assertThrows(GcsUri.GcsUriException.class, () -> GcsUri.parse(""));
        assertThrows(GcsUri.GcsUriException.class, () -> GcsUri.parse(null));
        assertThrows(GcsUri.GcsUriException.class, () -> GcsUri.parse("gs:///no-bucket"));
        assertThrows(GcsUri.GcsUriException.class, () -> GcsUri.parse("https://example.com/b/k"));
        assertThrows(GcsUri.GcsUriException.class, () -> GcsUri.parse("ftp://b/k"));
    }
}
