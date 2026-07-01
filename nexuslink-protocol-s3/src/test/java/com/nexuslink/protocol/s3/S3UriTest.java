package com.nexuslink.protocol.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3UriTest {

    // ---- s3:// scheme -------------------------------------------------------

    @Test
    void s3SchemeWithKey() {
        S3Uri u = S3Uri.parse("s3://my-bucket/path/to/object.txt");
        assertEquals(S3Uri.Style.S3_SCHEME, u.style());
        assertEquals("my-bucket", u.bucket());
        assertEquals("path/to/object.txt", u.key());
        assertNull(u.region());
        assertNull(u.endpoint());
        assertTrue(u.hasKey());
    }

    @Test
    void s3SchemeBucketOnly() {
        S3Uri u = S3Uri.parse("s3://my-bucket");
        assertEquals("my-bucket", u.bucket());
        assertEquals("", u.key());
        assertFalse(u.hasKey());
        assertEquals("s3://my-bucket", u.toS3Uri());
    }

    @Test
    void s3SchemeTrailingSlashIsBucketOnly() {
        S3Uri u = S3Uri.parse("s3://my-bucket/");
        assertEquals("my-bucket", u.bucket());
        assertEquals("", u.key());
    }

    @Test
    void s3SchemeKeyWithManySlashes() {
        S3Uri u = S3Uri.parse("s3://logs/2026/07/01/app.log");
        assertEquals("logs", u.bucket());
        assertEquals("2026/07/01/app.log", u.key());
    }

    // ---- virtual-hosted style ----------------------------------------------

    @Test
    void virtualHostedNoRegion() {
        S3Uri u = S3Uri.parse("https://bucket.s3.amazonaws.com/key");
        assertEquals(S3Uri.Style.VIRTUAL_HOSTED, u.style());
        assertEquals("bucket", u.bucket());
        assertEquals("key", u.key());
        assertNull(u.region());
        assertNull(u.endpoint());
    }

    @Test
    void virtualHostedDottedRegion() {
        S3Uri u = S3Uri.parse("https://bucket.s3.us-east-1.amazonaws.com/dir/key");
        assertEquals(S3Uri.Style.VIRTUAL_HOSTED, u.style());
        assertEquals("bucket", u.bucket());
        assertEquals("us-east-1", u.region());
        assertEquals("dir/key", u.key());
    }

    @Test
    void virtualHostedDashRegion() {
        S3Uri u = S3Uri.parse("https://bucket.s3-eu-west-1.amazonaws.com/key");
        assertEquals(S3Uri.Style.VIRTUAL_HOSTED, u.style());
        assertEquals("bucket", u.bucket());
        assertEquals("eu-west-1", u.region());
        assertEquals("key", u.key());
    }

    @Test
    void virtualHostedDottedBucketName() {
        S3Uri u = S3Uri.parse("https://my.dotted.bucket.s3.amazonaws.com/k");
        assertEquals("my.dotted.bucket", u.bucket());
        assertEquals("k", u.key());
        assertNull(u.region());
    }

    @Test
    void virtualHostedBucketOnly() {
        S3Uri u = S3Uri.parse("https://bucket.s3.us-east-1.amazonaws.com");
        assertEquals("bucket", u.bucket());
        assertEquals("", u.key());
        assertEquals("us-east-1", u.region());
    }

    // ---- path style ---------------------------------------------------------

    @Test
    void pathStyleAwsNoRegion() {
        S3Uri u = S3Uri.parse("https://s3.amazonaws.com/my-bucket/some/key");
        assertEquals(S3Uri.Style.PATH, u.style());
        assertEquals("my-bucket", u.bucket());
        assertEquals("some/key", u.key());
        assertNull(u.region());
        assertEquals("s3.amazonaws.com", u.endpoint());
    }

    @Test
    void pathStyleAwsWithRegion() {
        S3Uri u = S3Uri.parse("https://s3.us-west-2.amazonaws.com/my-bucket/key");
        assertEquals(S3Uri.Style.PATH, u.style());
        assertEquals("my-bucket", u.bucket());
        assertEquals("key", u.key());
        assertEquals("us-west-2", u.region());
        assertEquals("s3.us-west-2.amazonaws.com", u.endpoint());
    }

    @Test
    void pathStyleCustomEndpointLocalstack() {
        S3Uri u = S3Uri.parse("http://localhost:4566/my-bucket/nested/obj.json");
        assertEquals(S3Uri.Style.PATH, u.style());
        assertEquals("my-bucket", u.bucket());
        assertEquals("nested/obj.json", u.key());
        assertNull(u.region());
        assertEquals("localhost:4566", u.endpoint());
    }

    @Test
    void pathStyleCustomEndpointBucketOnly() {
        S3Uri u = S3Uri.parse("http://localhost:4566/only-bucket");
        assertEquals("only-bucket", u.bucket());
        assertEquals("", u.key());
        assertEquals("localhost:4566", u.endpoint());
    }

    // ---- decoding & canonicalisation ---------------------------------------

    @Test
    void keyIsUrlDecoded() {
        S3Uri u = S3Uri.parse("https://bucket.s3.amazonaws.com/my%20folder/a%2Bb.txt");
        assertEquals("my folder/a+b.txt", u.key());
    }

    @Test
    void plusSignIsPreservedNotSpace() {
        S3Uri u = S3Uri.parse("https://s3.amazonaws.com/bucket/a+b/c.txt");
        assertEquals("a+b/c.txt", u.key());
    }

    @Test
    void toS3UriCanonicalisesVirtualHosted() {
        S3Uri u = S3Uri.parse("https://bucket.s3.us-east-1.amazonaws.com/dir/key");
        assertEquals("s3://bucket/dir/key", u.toS3Uri());
    }

    @Test
    void toS3UriCanonicalisesPathStyle() {
        S3Uri u = S3Uri.parse("http://localhost:4566/my-bucket/nested/obj.json");
        assertEquals("s3://my-bucket/nested/obj.json", u.toS3Uri());
    }

    @Test
    void toS3UriRoundTripsS3Scheme() {
        String s = "s3://logs/2026/07/01/app.log";
        assertEquals(s, S3Uri.parse(s).toS3Uri());
    }

    @Test
    void equalityOfEquivalentLocations() {
        // Not equal because style/endpoint differ, but both point at the same object.
        S3Uri a = S3Uri.parse("s3://b/k");
        S3Uri b = S3Uri.parse("s3://b/k");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ---- malformed inputs ---------------------------------------------------

    @Test
    void nullThrows() {
        assertThrows(S3Uri.S3UriException.class, () -> S3Uri.parse(null));
    }

    @Test
    void blankThrows() {
        assertThrows(S3Uri.S3UriException.class, () -> S3Uri.parse("   "));
    }

    @Test
    void unknownSchemeThrows() {
        assertThrows(S3Uri.S3UriException.class, () -> S3Uri.parse("ftp://host/bucket/key"));
    }

    @Test
    void s3SchemeWithoutBucketThrows() {
        assertThrows(S3Uri.S3UriException.class, () -> S3Uri.parse("s3:///key-without-bucket"));
    }

    @Test
    void pathStyleWithoutBucketThrows() {
        assertThrows(S3Uri.S3UriException.class, () -> S3Uri.parse("http://localhost:4566/"));
    }

    @Test
    void plainTextThrows() {
        assertThrows(S3Uri.S3UriException.class, () -> S3Uri.parse("just-some-text"));
    }
}
