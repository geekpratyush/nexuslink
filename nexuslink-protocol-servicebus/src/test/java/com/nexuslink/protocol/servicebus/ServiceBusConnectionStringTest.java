package com.nexuslink.protocol.servicebus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceBusConnectionStringTest {

    @Test
    void parsesRealNamespaceString() {
        ServiceBusConnectionString cs = ServiceBusConnectionString.parse(
                "Endpoint=sb://my-ns.servicebus.windows.net/;"
                        + "SharedAccessKeyName=RootManageSharedAccessKey;"
                        + "SharedAccessKey=abc123def456==");
        assertEquals("sb://my-ns.servicebus.windows.net/", cs.endpoint());
        assertEquals("RootManageSharedAccessKey", cs.sharedAccessKeyName());
        assertEquals("abc123def456==", cs.sharedAccessKey());
        assertEquals("my-ns.servicebus.windows.net", cs.namespace());
        assertNull(cs.entityPath());
        assertFalse(cs.isDevelopment());
    }

    @Test
    void splitsOnFirstEqualsSoKeyPaddingSurvives() {
        // The SAS key itself contains '=' padding; only the first '=' delimits the pair.
        ServiceBusConnectionString cs = ServiceBusConnectionString.parse(
                "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=k;SharedAccessKey=aa==bb==");
        assertEquals("aa==bb==", cs.sharedAccessKey());
    }

    @Test
    void recognisesEmulatorFlagAndHost() {
        ServiceBusConnectionString cs = ServiceBusConnectionString.parse(
                "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
                        + "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;");
        assertTrue(cs.isDevelopment());
        assertEquals("localhost", cs.namespace());
    }

    @Test
    void stripsSchemeSlashAndPortFromNamespace() {
        assertEquals("localhost", ServiceBusConnectionString.parse(
                "Endpoint=sb://localhost:5672/").namespace());
        assertEquals("ns.servicebus.windows.net", ServiceBusConnectionString.parse(
                "Endpoint=amqps://ns.servicebus.windows.net/;EntityPath=q1").namespace());
    }

    @Test
    void capturesEntityPath() {
        ServiceBusConnectionString cs = ServiceBusConnectionString.parse(
                "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=k;SharedAccessKey=v;EntityPath=orders");
        assertEquals("orders", cs.entityPath());
    }

    @Test
    void keysAreCaseInsensitiveAndSeparatorsTolerant() {
        ServiceBusConnectionString cs = ServiceBusConnectionString.parse(
                "endpoint=sb://ns.servicebus.windows.net/;;SHAREDACCESSKEYNAME=k;sharedaccesskey=v;");
        assertEquals("k", cs.sharedAccessKeyName());
        assertEquals("v", cs.sharedAccessKey());
    }

    @Test
    void rejectsEmpty() {
        assertThrows(ServiceBusConnectionString.MalformedConnectionStringException.class,
                () -> ServiceBusConnectionString.parse("  "));
        assertThrows(ServiceBusConnectionString.MalformedConnectionStringException.class,
                () -> ServiceBusConnectionString.parse(null));
    }

    @Test
    void rejectsMissingEndpoint() {
        assertThrows(ServiceBusConnectionString.MalformedConnectionStringException.class,
                () -> ServiceBusConnectionString.parse("SharedAccessKeyName=k;SharedAccessKey=v"));
    }

    @Test
    void rejectsBadEndpointScheme() {
        assertThrows(ServiceBusConnectionString.MalformedConnectionStringException.class,
                () -> ServiceBusConnectionString.parse("Endpoint=https://ns.servicebus.windows.net/"));
    }

    @Test
    void rejectsMalformedPair() {
        assertThrows(ServiceBusConnectionString.MalformedConnectionStringException.class,
                () -> ServiceBusConnectionString.parse("Endpoint=sb://ns/;justakey"));
    }

    @Test
    void rejectsBadEmulatorFlag() {
        assertThrows(ServiceBusConnectionString.MalformedConnectionStringException.class,
                () -> ServiceBusConnectionString.parse("Endpoint=sb://localhost;UseDevelopmentEmulator=maybe"));
    }

    @Test
    void redactedMasksTheKey() {
        String redacted = ServiceBusConnectionString.parse(
                "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=k;SharedAccessKey=secret==")
                .redacted();
        assertTrue(redacted.contains("***"));
        assertFalse(redacted.contains("secret=="));
    }

    @Test
    void equalsAndHashCode() {
        String s = "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=k;SharedAccessKey=v";
        assertEquals(ServiceBusConnectionString.parse(s), ServiceBusConnectionString.parse(s));
        assertEquals(ServiceBusConnectionString.parse(s).hashCode(), ServiceBusConnectionString.parse(s).hashCode());
        assertNotEquals(ServiceBusConnectionString.parse(s),
                ServiceBusConnectionString.parse(s + ";EntityPath=q"));
    }
}
