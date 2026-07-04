package com.nexuslink.core.connection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileImportExportTest {

    private final ProfileImportExport io = new ProfileImportExport();

    private static List<ConnectionProfile> sample() {
        ConnectionProfile a = new ConnectionProfile("Prod API", ConnectionProfile.Protocol.REST, "https://api.example.com")
                .withAuth(AuthMethod.BEARER_TOKEN).authProp("tokenRef", "vault:prod-token").prop("timeout", "30");
        ConnectionProfile b = new ConnectionProfile("Analytics DB", ConnectionProfile.Protocol.SQL,
                "jdbc:postgresql://db/analytics").withUser("reader");
        return List.of(a, b);
    }

    @Test
    void roundTripsProfilesThroughAnEncryptedBundle() throws Exception {
        char[] pass = "s3cret-pass".toCharArray();
        String bundle = io.export(sample(), pass);
        List<ConnectionProfile> back = io.importBundle(bundle, "s3cret-pass".toCharArray());

        assertEquals(2, back.size());
        assertEquals("Prod API", back.get(0).name);
        assertEquals(ConnectionProfile.Protocol.REST, back.get(0).protocol);
        assertEquals("https://api.example.com", back.get(0).target);
        assertEquals(AuthMethod.BEARER_TOKEN, back.get(0).auth);
        assertEquals("vault:prod-token", back.get(0).authProps.get("tokenRef"));
        assertEquals("30", back.get(0).properties.get("timeout"));
        assertEquals("reader", back.get(1).username);
    }

    @Test
    void bundleIsEncryptedNotPlaintext() throws Exception {
        String bundle = io.export(sample(), "pw".toCharArray());
        // The endpoint/target must not appear in the bundle in the clear.
        assertFalse(bundle.contains("api.example.com"));
        assertFalse(bundle.contains("Analytics DB"));
        assertTrue(bundle.contains("nexuslink-profiles"));
        assertTrue(bundle.contains("ciphertext"));
    }

    @Test
    void wrongPassphraseFails() throws Exception {
        String bundle = io.export(sample(), "correct".toCharArray());
        ProfileImportExport.ProfileBundleException ex = assertThrows(
                ProfileImportExport.ProfileBundleException.class,
                () -> io.importBundle(bundle, "wrong".toCharArray()));
        assertTrue(ex.getMessage().toLowerCase().contains("passphrase"));
    }

    @Test
    void tamperedCiphertextFails() throws Exception {
        String bundle = io.export(sample(), "pw".toCharArray());
        // Flip a character inside the base64 ciphertext value.
        int idx = bundle.indexOf("ciphertext") + 20;
        char c = bundle.charAt(idx);
        String tampered = bundle.substring(0, idx) + (c == 'A' ? 'B' : 'A') + bundle.substring(idx + 1);
        assertThrows(ProfileImportExport.ProfileBundleException.class,
                () -> io.importBundle(tampered, "pw".toCharArray()));
    }

    @Test
    void foreignBundleIsRejected() {
        ProfileImportExport.ProfileBundleException ex = assertThrows(
                ProfileImportExport.ProfileBundleException.class,
                () -> io.importBundle("{\"format\":\"something-else\"}", "pw".toCharArray()));
        assertTrue(ex.getMessage().contains("NexusLink profile bundle"));
    }

    @Test
    void unreadableJsonIsRejected() {
        assertThrows(ProfileImportExport.ProfileBundleException.class,
                () -> io.importBundle("not json at all", "pw".toCharArray()));
    }

    @Test
    void emptyListRoundTrips() throws Exception {
        String bundle = io.export(List.of(), "pw".toCharArray());
        assertTrue(io.importBundle(bundle, "pw".toCharArray()).isEmpty());
    }

    @Test
    void eachExportUsesFreshSaltAndIv() throws Exception {
        // Two exports of the same data must differ (random salt+iv), so bundles aren't comparable blobs.
        String one = io.export(sample(), "pw".toCharArray());
        String two = io.export(sample(), "pw".toCharArray());
        assertNotEquals(one, two);
    }
}
