package com.nexuslink.protocol.mongo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure (no-server) tests for {@link MongoConnectionString}. */
class MongoConnectionStringTest {

    @Test
    void basicSingleHostWithExplicitPortAndDb() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://host:27017/mydb");
        assertFalse(cs.isSrv());
        assertEquals(List.of("host:27017"), cs.hosts());
        assertEquals("mydb", cs.authDatabase().orElseThrow());
        assertTrue(cs.username().isEmpty());
        assertTrue(cs.password().isEmpty());
        assertTrue(cs.options().isEmpty());
    }

    @Test
    void defaultPortIsFilledIn() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://example.com");
        assertEquals(List.of("example.com:27017"), cs.hosts());
        assertTrue(cs.authDatabase().isEmpty());
    }

    @Test
    void multiHostWithReplicaSetOption() {
        MongoConnectionString cs = MongoConnectionString.parse(
                "mongodb://a.example:27017,b.example,c.example:27019/admin?replicaSet=rs0");
        assertEquals(List.of("a.example:27017", "b.example:27017", "c.example:27019"), cs.hosts());
        assertEquals("admin", cs.authDatabase().orElseThrow());
        assertEquals("rs0", cs.replicaSet().orElseThrow());
    }

    @Test
    void srvUriWithCredentialsAndOptions() {
        MongoConnectionString cs = MongoConnectionString.parse(
                "mongodb+srv://user:pass@cluster.example.mongodb.net/db?retryWrites=true&w=majority");
        assertTrue(cs.isSrv());
        assertEquals(List.of("cluster.example.mongodb.net"), cs.hosts());
        assertEquals("user", cs.username().orElseThrow());
        assertEquals("pass", cs.password().orElseThrow());
        assertEquals("db", cs.authDatabase().orElseThrow());
        assertEquals(Boolean.TRUE, cs.retryWrites().orElseThrow());
        assertEquals("majority", cs.option("w").orElseThrow());
    }

    @Test
    void percentEncodedUserAndPasswordAreDecoded() {
        MongoConnectionString cs = MongoConnectionString.parse(
                "mongodb://us%40er:p%40ss%3Aword@host/db");
        assertEquals("us@er", cs.username().orElseThrow());
        assertEquals("p@ss:word", cs.password().orElseThrow());
    }

    @Test
    void plusInPasswordIsNotTreatedAsSpace() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://u:a+b@host/db");
        assertEquals("a+b", cs.password().orElseThrow());
    }

    @Test
    void optionKeysAreCaseInsensitive() {
        MongoConnectionString cs = MongoConnectionString.parse(
                "mongodb://host/db?ReplicaSet=rs1&AuthSource=admin&TLS=true");
        assertEquals("rs1", cs.replicaSet().orElseThrow());
        assertEquals("admin", cs.authSource().orElseThrow());
        assertEquals(Boolean.TRUE, cs.tls().orElseThrow());
        assertEquals("rs1", cs.option("REPLICASET").orElseThrow());
    }

    @Test
    void tlsFallsBackToSslAlias() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://host/?ssl=false");
        assertEquals(Boolean.FALSE, cs.tls().orElseThrow());
    }

    @Test
    void ipv6HostWithPort() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://[::1]:27018/db");
        assertEquals(List.of("[::1]:27018"), cs.hosts());
    }

    @Test
    void ipv6HostDefaultPort() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://[fe80::1]/db");
        assertEquals(List.of("[fe80::1]:27017"), cs.hosts());
    }

    @Test
    void trailingSlashWithoutDbMeansNoAuthDatabase() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://host/?tls=true");
        assertTrue(cs.authDatabase().isEmpty());
        assertEquals(Boolean.TRUE, cs.tls().orElseThrow());
    }

    @Test
    void usernameWithoutPassword() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://onlyuser@host/db");
        assertEquals("onlyuser", cs.username().orElseThrow());
        assertTrue(cs.password().isEmpty());
    }

    @Test
    void missingSchemeThrows() {
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("host:27017/db"));
    }

    @Test
    void nullOrBlankThrows() {
        assertThrows(MongoConnectionStringException.class, () -> MongoConnectionString.parse(null));
        assertThrows(MongoConnectionStringException.class, () -> MongoConnectionString.parse("   "));
    }

    @Test
    void emptyHostThrows() {
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb:///db"));
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb://a.example,,b.example/db"));
    }

    @Test
    void srvWithPortThrows() {
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb+srv://cluster.example.net:27017/db"));
    }

    @Test
    void srvWithMultipleHostsThrows() {
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb+srv://a.example.net,b.example.net/db"));
    }

    @Test
    void invalidPortThrows() {
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb://host:notaport/db"));
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb://host:99999/db"));
    }

    @Test
    void badBooleanOptionThrows() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://host/?retryWrites=maybe");
        assertThrows(MongoConnectionStringException.class, cs::retryWrites);
    }

    @Test
    void truncatedPercentEscapeThrows() {
        assertThrows(MongoConnectionStringException.class,
                () -> MongoConnectionString.parse("mongodb://u:p%4@host/db"));
    }

    @Test
    void redactedHidesPasswordAndRoundTripsStructure() {
        MongoConnectionString cs = MongoConnectionString.parse(
                "mongodb://user:secret@a.example:27017,b.example:27017/mydb?replicaSet=rs0&tls=true");
        String redacted = cs.redacted();
        assertFalse(redacted.contains("secret"));
        assertTrue(redacted.contains(":****@"));
        assertTrue(redacted.contains("a.example:27017,b.example:27017"));
        assertTrue(redacted.contains("replicaset=rs0"));
        assertEquals(redacted, cs.toString());
    }

    @Test
    void redactedWithoutCredentials() {
        MongoConnectionString cs = MongoConnectionString.parse("mongodb://host:27017/db");
        assertEquals("mongodb://host:27017/db", cs.redacted());
    }
}
