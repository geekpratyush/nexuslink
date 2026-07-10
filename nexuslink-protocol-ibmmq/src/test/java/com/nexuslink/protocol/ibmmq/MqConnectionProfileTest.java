package com.nexuslink.protocol.ibmmq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MqConnectionProfileTest {

    private static MqConnectionProfile dev() {
        return MqConnectionProfile.plain("QM1", "DEV.APP.SVRCONN", "localhost", 1414, "app", "nexus123");
    }

    @Test
    void plainProfileHasNoSecurityLayersEnabled() {
        MqConnectionProfile profile = dev();

        assertFalse(profile.tls());
        assertFalse(profile.ams());
        assertTrue(profile.hasCredentials());
    }

    @Test
    void connectionNameUsesIbmsHostPortNotation() {
        assertEquals("localhost(1414)", dev().connectionName());
    }

    @Test
    void blankUserMeansNoCredentials() {
        MqConnectionProfile anonymous =
                MqConnectionProfile.plain("QM1", "DEV.APP.SVRCONN", "localhost", 1414, "  ", null);

        assertFalse(anonymous.hasCredentials());
    }

    @Test
    void withTlsAndWithAmsLayerOnIndependently() {
        MqConnectionProfile secured = dev()
                .withTls("TLS_AES_256_GCM_SHA384", "/keys/trust.p12", "secret")
                .withAms("/keys/keystore.conf");

        assertTrue(secured.tls());
        assertEquals("TLS_AES_256_GCM_SHA384", secured.cipherSuite());
        assertTrue(secured.ams());
        assertEquals("/keys/keystore.conf", secured.amsKeystoreConf());
        // layering must not disturb the transport coordinates
        assertEquals("QM1", secured.queueManager());
        assertEquals(1414, secured.port());
    }

    @Test
    void requiredFieldsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> MqConnectionProfile.plain(" ", "CH", "h", 1414, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MqConnectionProfile.plain("QM1", " ", "h", 1414, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MqConnectionProfile.plain("QM1", "CH", " ", 1414, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MqConnectionProfile.plain("QM1", "CH", "h", 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MqConnectionProfile.plain("QM1", "CH", "h", 70000, null, null));
    }

    @Test
    void tlsWithoutCipherSuiteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MqConnectionProfile(
                "QM1", "CH", "h", 1414, null, null, true, " ", null, null, false, null));
    }

    @Test
    void amsWithoutKeystoreConfIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MqConnectionProfile(
                "QM1", "CH", "h", 1414, null, null, false, null, null, null, true, null));
    }

    @Test
    void redactedNeverLeaksThePassword() {
        String redacted = dev().withTls("TLS_AES_256_GCM_SHA384", "/k/t.p12", "trustsecret").redacted();

        assertFalse(redacted.contains("nexus123"));
        assertFalse(redacted.contains("trustsecret"));
        assertTrue(redacted.contains("QM1"));
        assertTrue(redacted.contains("localhost(1414)"));
        assertEquals(redacted, dev().withTls("TLS_AES_256_GCM_SHA384", "/k/t.p12", "trustsecret").toString());
    }

    @Test
    void redactedShowsSecurityPosture() {
        assertTrue(dev().redacted().contains("tls=off"));
        assertTrue(dev().redacted().contains("ams=off"));
        assertTrue(dev().withAms("/k/keystore.conf").redacted().contains("ams=on"));
    }
}
