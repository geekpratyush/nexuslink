package com.nexuslink.core.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileValidatorTest {

    private static ConnectionProfile profile(ConnectionProfile.Protocol protocol, String target) {
        return new ConnectionProfile("p", protocol, target);
    }

    @Test
    void validRestProfilePasses() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "https://api.example.com/v1");
        assertTrue(ProfileValidator.validate(p).valid());
    }

    @Test
    void blankNameAndTargetAreReported() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "");
        p.name = "";
        ProfileValidator.Result r = ProfileValidator.validate(p);
        assertFalse(r.valid());
        assertTrue(r.summary().contains("Name is required"));
        assertTrue(r.summary().contains("endpoint is required"));
    }

    @Test
    void restRejectsNonHttpScheme() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "ftp://nope");
        assertFalse(ProfileValidator.validate(p).valid());
    }

    @Test
    void websocketRequiresWsScheme() {
        assertTrue(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.WEBSOCKET, "wss://host/socket")).valid());
        assertFalse(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.WEBSOCKET, "https://host")).valid());
    }

    @Test
    void jdbcUrlMustStartWithJdbc() {
        assertTrue(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.SQL, "jdbc:postgresql://h:5432/db")).valid());
        assertFalse(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.SQL, "postgresql://h:5432/db")).valid());
    }

    @Test
    void mongoRequiresMongoScheme() {
        assertTrue(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.MONGO, "mongodb+srv://cluster/db")).valid());
        assertFalse(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.MONGO, "http://cluster")).valid());
    }

    @Test
    void kafkaBootstrapAcceptsCommaSeparatedHostPorts() {
        assertTrue(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.KAFKA, "a:9092,b:9092")).valid());
        assertFalse(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.KAFKA, "a,b")).valid());
    }

    @Test
    void mqttRequiresTransportScheme() {
        assertTrue(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.MQTT, "ssl://broker:8883")).valid());
        assertFalse(ProfileValidator.validate(
                profile(ConnectionProfile.Protocol.MQTT, "broker:1883")).valid());
    }

    @Test
    void llmNeedsNoTarget() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.LLM, "");
        assertTrue(ProfileValidator.validate(p).valid());
    }

    @Test
    void basicAuthRequiresUsername() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "https://api");
        p.auth = AuthMethod.BASIC;
        assertFalse(ProfileValidator.validate(p).valid());
        p.username = "alice";
        assertTrue(ProfileValidator.validate(p).valid());
    }

    @Test
    void bearerAuthRequiresTokenRef() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "https://api");
        p.auth = AuthMethod.BEARER_TOKEN;
        assertFalse(ProfileValidator.validate(p).valid());
        p.authProp("tokenRef", "vault:my-token");
        assertTrue(ProfileValidator.validate(p).valid());
    }

    @Test
    void oauth2RequiresTokenUrlAndClientId() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "https://api");
        p.auth = AuthMethod.OAUTH2;
        ProfileValidator.Result r = ProfileValidator.validate(p);
        assertFalse(r.valid());
        assertTrue(r.summary().contains("token URL"));
        assertTrue(r.summary().contains("client id"));
        p.authProp("tokenUrl", "https://auth/token").authProp("clientId", "app");
        assertTrue(ProfileValidator.validate(p).valid());
    }

    @Test
    void mutualTlsRequiresKeystore() {
        ConnectionProfile p = profile(ConnectionProfile.Protocol.REST, "https://api");
        p.auth = AuthMethod.MUTUAL_TLS;
        assertFalse(ProfileValidator.validate(p).valid());
        p.authProp("keystorePath", "/etc/certs/client.p12");
        assertTrue(ProfileValidator.validate(p).valid());
    }
}
