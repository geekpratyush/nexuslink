package com.nexuslink.ui.mongo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Connection strings assembled by the Mongo auth panel. */
class MongoAuthUriTest {

    @Test
    void plainHostAndPortNeedsNoCredentials() {
        assertEquals("mongodb://localhost:27017/?authSource=admin",
                MongoClientView.buildUri("localhost", "27017", "", "", "admin", "(default)", false, false, false));
    }

    @Test
    void credentialsMechanismAndTlsAreEncodedIntoTheUri() {
        String uri = MongoClientView.buildUri("db.example.com", "27018", "app", "p@ss word", "admin",
                "SCRAM-SHA-256", true, false, false);
        assertEquals("mongodb://app:p%40ss%20word@db.example.com:27018/"
                + "?authSource=admin&authMechanism=SCRAM-SHA-256&tls=true", uri);
    }

    @Test
    void srvDropsThePortAndTheRedundantTlsOption() {
        String uri = MongoClientView.buildUri("cluster0.mongodb.net", "27017", "app", "secret", "admin",
                "(default)", true, true, false);
        assertEquals("mongodb+srv://app:secret@cluster0.mongodb.net/?authSource=admin", uri);
    }

    @Test
    void previewMasksThePassword() {
        String uri = MongoClientView.buildUri("h", "27017", "app", "secret", "admin", "(default)", false, false, true);
        assertTrue(uri.contains("app:••••@h"), uri);
        assertTrue(!uri.contains("secret"), uri);
    }

    @Test
    void blankHostFallsBackToLocalhost() {
        assertEquals("mongodb://localhost:27017/",
                MongoClientView.buildUri("  ", "27017", "", "", "", "(default)", false, false, false));
    }
}
