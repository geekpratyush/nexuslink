package com.nexuslink.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UriRedactorTest {

    @Test
    void aPasswordInTheUserinfoIsHiddenButTheUserIsKept() {
        assertEquals("mongodb://app:" + UriRedactor.MASK + "@db.internal:27017/orders",
                UriRedactor.redact("mongodb://app:s3cret@db.internal:27017/orders"));
        assertEquals("amqp://nexus:" + UriRedactor.MASK + "@broker:5672",
                UriRedactor.redact("amqp://nexus:hunter2@broker:5672"));
    }

    @Test
    void redisPasswordOnlyUserinfoIsHidden() {
        assertEquals("redis://:" + UriRedactor.MASK + "@cache:6379",
                UriRedactor.redact("redis://:s3cret@cache:6379"));
    }

    @Test
    void aUriWithNoPasswordIsLeftAlone() {
        String plain = "mongodb://db.internal:27017/orders";
        assertEquals(plain, UriRedactor.redact(plain));
        assertEquals("kafka://broker:9092", UriRedactor.redact("kafka://broker:9092"));
        assertFalse(UriRedactor.carriesCredentials(plain));
    }

    @Test
    void aUsernameWithoutAPasswordKeepsItsAtSign() {
        assertEquals("sftp://ada@files.internal", UriRedactor.redact("sftp://ada@files.internal"));
    }

    @Test
    void jdbcPasswordPropertiesAreHidden() {
        assertEquals("jdbc:postgresql://db/orders?user=app&password=" + UriRedactor.MASK,
                UriRedactor.redact("jdbc:postgresql://db/orders?user=app&password=pw"));
        assertEquals("jdbc:sqlserver://db;databaseName=x;password=" + UriRedactor.MASK,
                UriRedactor.redact("jdbc:sqlserver://db;databaseName=x;password=pw"));
    }

    @Test
    void theOtherSecretParameterNamesAreHiddenToo() {
        for (String name : new String[]{"token", "secret", "apiKey", "access_key", "sessionToken",
                "pwd", "privateKey"}) {
            String uri = "https://api.test/v1?x=1&" + name + "=abc123";
            assertTrue(UriRedactor.redact(uri).endsWith(name + "=" + UriRedactor.MASK),
                    name + " → " + UriRedactor.redact(uri));
        }
    }

    @Test
    void maskingIsCaseInsensitiveOnTheParameterName() {
        assertTrue(UriRedactor.redact("https://h/?PASSWORD=pw").endsWith(UriRedactor.MASK));
    }

    @Test
    void carryingCredentialsIsDetected() {
        assertTrue(UriRedactor.carriesCredentials("mongodb://a:b@h/db"));
        assertTrue(UriRedactor.carriesCredentials("jdbc:h2:mem:x?password=p"));
        assertFalse(UriRedactor.carriesCredentials("jdbc:h2:mem:x"));
        assertFalse(UriRedactor.carriesCredentials(""));
        assertFalse(UriRedactor.carriesCredentials(null));
    }

    @Test
    void theShortLabelIsTheHostAndDatabaseWithoutCredentials() {
        assertEquals("db.internal:27017/orders",
                UriRedactor.shortLabel("mongodb://app:s3cret@db.internal:27017/orders"));
        assertEquals("cache:6379", UriRedactor.shortLabel("redis://:pw@cache:6379"));
    }

    @Test
    void aJdbcLabelReadsAsEngineThenHostWithNoLeftoverSlashes() {
        assertEquals("mysql mysql-rfam-public.ebi.ac.uk:4497/Rfam",
                UriRedactor.shortLabel("jdbc:mysql://mysql-rfam-public.ebi.ac.uk:4497/Rfam"));
        assertEquals("postgresql db.internal:5432/orders",
                UriRedactor.shortLabel("jdbc:postgresql://db.internal:5432/orders?user=app&password=pw"));
        assertEquals("sqlite /var/data/app.db", UriRedactor.shortLabel("jdbc:sqlite:/var/data/app.db"));
    }

    @Test
    void aJdbcLabelNamesTheEngine() {
        assertTrue(UriRedactor.shortLabel("jdbc:mysql://h/db").startsWith("mysql"));
        assertTrue(UriRedactor.shortLabel("jdbc:oracle:thin:@//db:1521/ORCL").startsWith("oracle"));
    }

    @Test
    void theLabelKeepsTheParametersThatChangeWhichServerIsMeant() {
        String label = UriRedactor.shortLabel(
                "mongodb://a:b@h1:27017,h2:27017/orders?replicaSet=rs0&password=pw&maxPoolSize=50");
        assertTrue(label.contains("replicaSet=rs0"), label);
        assertFalse(label.contains("maxPoolSize"), label);
        assertFalse(label.contains("pw"), label);
    }

    @Test
    void anUnrecognisedShapeFallsBackToTheRedactedString() {
        assertEquals("not-a-uri", UriRedactor.shortLabel("not-a-uri"));
        assertEquals("", UriRedactor.shortLabel(""));
        assertEquals("", UriRedactor.shortLabel(null));
    }

    @Test
    void theDisplayNamePrefersTheCallersOwnName() {
        assertEquals("Orders prod", UriRedactor.displayName("Orders prod", "mongodb://a:b@h/db"));
        assertEquals("h/db", UriRedactor.displayName("  ", "mongodb://a:b@h/db"));
        assertEquals("Not connected", UriRedactor.displayName(null, ""));
    }
}
