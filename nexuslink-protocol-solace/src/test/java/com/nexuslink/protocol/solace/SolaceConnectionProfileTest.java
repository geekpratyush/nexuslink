package com.nexuslink.protocol.solace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SolaceConnectionProfileTest {

    @Test
    void singleHostConvenienceBuildsAOneEntryList() {
        SolaceConnectionProfile profile =
                SolaceConnectionProfile.single("tcp://localhost:55555", "default", "admin", "nexus123");

        assertEquals(List.of("tcp://localhost:55555"), profile.hosts());
        assertEquals("tcp://localhost:55555", profile.hostList());
    }

    @Test
    void hostListJoinsRedundancyPairWithComma() {
        SolaceConnectionProfile profile = new SolaceConnectionProfile(
                List.of("tcp://primary:55555", "tcp://backup:55555"), "default", "admin", "pw");

        assertEquals("tcp://primary:55555,tcp://backup:55555", profile.hostList());
    }

    @Test
    void hostsAreDefensivelyCopied() {
        List<String> mutable = new java.util.ArrayList<>(List.of("h1"));
        SolaceConnectionProfile profile = new SolaceConnectionProfile(mutable, "default", "admin", "pw");
        mutable.add("h2");

        assertEquals(List.of("h1"), profile.hosts(), "mutating the source list must not affect the profile");
        assertThrows(UnsupportedOperationException.class, () -> profile.hosts().add("x"));
    }

    @Test
    void emptyOrBlankHostsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SolaceConnectionProfile(List.of(), "default", "admin", "pw"));
        assertThrows(IllegalArgumentException.class,
                () -> new SolaceConnectionProfile(List.of("ok", "  "), "default", "admin", "pw"));
    }

    @Test
    void vpnAndUsernameRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> SolaceConnectionProfile.single("h", " ", "admin", "pw"));
        assertThrows(IllegalArgumentException.class,
                () -> SolaceConnectionProfile.single("h", "default", " ", "pw"));
    }

    @Test
    void blankPasswordIsAllowed() {
        assertDoesNotThrow(() -> SolaceConnectionProfile.single("h", "default", "admin", ""));
        assertDoesNotThrow(() -> SolaceConnectionProfile.single("h", "default", "admin", null));
    }

    @Test
    void redactedNeverLeaksThePassword() {
        String redacted = SolaceConnectionProfile.single("h", "default", "admin", "topsecret").redacted();

        assertFalse(redacted.contains("topsecret"));
        assertTrue(redacted.contains("default"));
        assertTrue(redacted.contains("admin"));
        assertEquals(redacted, SolaceConnectionProfile.single("h", "default", "admin", "topsecret").toString());
    }
}
