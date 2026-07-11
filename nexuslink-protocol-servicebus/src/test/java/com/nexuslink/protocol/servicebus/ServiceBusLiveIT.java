package com.nexuslink.protocol.servicebus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Service Bus test against the local {@code test-env} Azure Service Bus emulator (behind the
 * opt-in {@code proprietary} compose profile).
 * <p>
 * The emulator's entities are pre-provisioned from its config file (it has no management API), so this
 * test only exercises the send/receive data plane against a queue that already exists. Point it at the
 * emulator (or a real namespace) with:
 * <pre>docker compose -f test-env/docker-compose.yml --profile proprietary up -d servicebus
 * SERVICEBUS_CONNECTION_STRING="Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;\
 * SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;" SERVICEBUS_QUEUE=queue.1 \
 * mvn -pl nexuslink-protocol-servicebus test -Dnexuslink.it=true -Dtest=ServiceBusLiveIT</pre>
 * Gated on BOTH {@code -Dnexuslink.it=true} and the connection-string env var.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
@EnabledIfEnvironmentVariable(named = "SERVICEBUS_CONNECTION_STRING", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServiceBusLiveIT {

    private static String connectionString() {
        return System.getenv("SERVICEBUS_CONNECTION_STRING");
    }

    private static String queue() {
        String q = System.getenv("SERVICEBUS_QUEUE");
        return q == null || q.isBlank() ? "queue.1" : q;
    }

    @Test
    void sendReceiveRoundTrip() throws Exception {
        ServiceBusService svc = new ServiceBusService();
        svc.connect(connectionString());
        assertTrue(svc.isConnected());

        String queue = queue();

        // Drain anything left behind from a prior run so we assert on our own messages.
        for (int i = 0; i < 5 && !svc.receiveFromQueue(queue, 50, false).isEmpty(); i++) { /* drain */ }

        String tag = "sb-" + System.nanoTime();
        String id1 = svc.sendToQueue(queue, tag + "-one");
        String id2 = svc.sendToQueue(queue, tag + "-two");
        assertNotNull(id1);
        assertNotEquals(id1, id2);

        List<String> bodies = new ArrayList<>();
        for (int attempt = 0; attempt < 10 && bodies.size() < 2; attempt++) {
            for (ServiceBusService.ReceivedMessage m : svc.receiveFromQueue(queue, 10, false)) {
                bodies.add(m.body());
            }
            if (bodies.size() < 2) Thread.sleep(200);
        }
        assertTrue(bodies.contains(tag + "-one"), "first message should be received and completed");
        assertTrue(bodies.contains(tag + "-two"), "second message should be received and completed");

        // Everything completed → the next receive is empty.
        assertTrue(svc.receiveFromQueue(queue, 10, false).isEmpty(), "no messages remain after completion");
    }
}
