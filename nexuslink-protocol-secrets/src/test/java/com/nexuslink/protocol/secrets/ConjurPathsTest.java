package com.nexuslink.protocol.secrets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for Conjur path building + identifier encoding — no server needed. */
class ConjurPathsTest {

    @Test
    void normalizeStripsTrailingSlash() {
        assertEquals("https://conjur.example.com", ConjurPaths.normalizeUrl(" https://conjur.example.com/ "));
        assertThrows(IllegalArgumentException.class, () -> ConjurPaths.normalizeUrl(" "));
    }

    @Test
    void authenticatePathEncodesLogin() {
        assertEquals("authn/myAccount/admin/authenticate",
                ConjurPaths.authenticatePath("myAccount", "admin"));
        // a host login "host/ci/deployer" — the slashes are encoded into one segment
        assertEquals("authn/myAccount/host%2Fci%2Fdeployer/authenticate",
                ConjurPaths.authenticatePath("myAccount", "host/ci/deployer"));
    }

    @Test
    void secretPathEncodesVariableId() {
        assertEquals("secrets/myAccount/variable/nexus%2Fdb%2Fpassword",
                ConjurPaths.secretPath("myAccount", "nexus/db/password"));
    }

    @Test
    void encodeSegmentUsesPercent20ForSpaces() {
        assertEquals("a%20b", ConjurPaths.encodeSegment("a b"));
    }

    @Test
    void authorizationHeaderWrapsToken() {
        assertEquals("Token token=\"YWJj\"", ConjurPaths.authorizationHeader("YWJj"));
    }
}
