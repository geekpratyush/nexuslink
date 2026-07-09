package com.nexuslink.protocol.pubsub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PubSubServiceTest {

    @Test
    void shortNameStripsTheResourcePrefix() {
        assertEquals("orders", PubSubService.shortName("projects/p/topics/orders"));
        assertEquals("orders-sub", PubSubService.shortName("projects/p/subscriptions/orders-sub"));
    }

    @Test
    void shortNameLeavesABareNameUnchanged() {
        assertEquals("orders", PubSubService.shortName("orders"));
        assertEquals("", PubSubService.shortName(null));
    }

    @Test
    void connectRequiresAProjectId() {
        assertThrows(IllegalArgumentException.class, () -> new PubSubService().connect(""));
        assertThrows(IllegalArgumentException.class, () -> new PubSubService().connect(null));
    }

    @Test
    void notConnectedUntilConnectSucceeds() {
        assertFalse(new PubSubService().isConnected());
    }
}
