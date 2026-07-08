package com.nexuslink.core.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionRegistryTest {

    /** Captures events on the caller thread ({@link EventBus#post} is synchronous). */
    private static final class Capture {
        final List<ConnectionEvent> events = new CopyOnWriteArrayList<>();
        final EventBus.Subscription sub;
        // strong ref to the listener so the weak-ref bus keeps it alive
        final java.util.function.Consumer<ConnectionEvent> listener = events::add;
        Capture(EventBus bus) { sub = bus.subscribe(ConnectionEvent.class, listener); }
    }

    @Test
    void countsAggregateByState() {
        var reg = new ConnectionRegistry(new EventBus());
        reg.active("a", "REST", "h1");
        reg.active("b", "REST", "h2");
        reg.idle("c", "Kafka", "b1");
        reg.failed("d", "Redis", "r1");

        var c = reg.counts();
        assertEquals(2, c.active());
        assertEquals(1, c.idle());
        assertEquals(1, c.failed());
        assertEquals(4, c.total());
    }

    @Test
    void reRecordMovesBetweenStates() {
        var reg = new ConnectionRegistry(new EventBus());
        reg.active("x", "REST", "h1");
        assertEquals(1, reg.counts().active());
        reg.idle("x", "REST", "h1");
        assertEquals(0, reg.counts().active());
        assertEquals(1, reg.counts().idle());
        reg.failed("x", "REST", "h1");
        assertEquals(1, reg.counts().failed());
        assertEquals(1, reg.counts().total()); // still one connection, not three
    }

    @Test
    void closedDropsFromTally() {
        var reg = new ConnectionRegistry(new EventBus());
        reg.active("x", "REST", "h1");
        reg.closed("x", "REST", "h1");
        assertEquals(0, reg.counts().total());
    }

    @Test
    void byProtocolBreaksDownPerLabel() {
        var reg = new ConnectionRegistry(new EventBus());
        reg.active("a", "REST", "h1");
        reg.idle("b", "REST", "h2");
        reg.failed("c", "Kafka", "b1");

        Map<String, ConnectionRegistry.Counts> m = reg.byProtocol();
        assertEquals(2, m.size());
        assertEquals(1, m.get("REST").active());
        assertEquals(1, m.get("REST").idle());
        assertEquals(1, m.get("Kafka").failed());
    }

    @Test
    void emitsEventOnRealTransitionOnly() {
        var bus = newBus();
        var cap = new Capture(bus);
        var reg = new ConnectionRegistry(bus);

        reg.active("x", "REST", "h1");
        reg.active("x", "REST", "h1"); // same state — no event
        reg.idle("x", "REST", "h1");   // real change — event

        assertEquals(2, cap.events.size());
        assertEquals(ConnState.ACTIVE, cap.events.get(0).state());
        assertEquals(ConnState.IDLE, cap.events.get(1).state());
    }

    @Test
    void closedEmitsOnlyWhenPresent() {
        var bus = newBus();
        var cap = new Capture(bus);
        var reg = new ConnectionRegistry(bus);

        reg.closed("ghost", "REST", "h1"); // unknown — no event
        assertTrue(cap.events.isEmpty());

        reg.active("x", "REST", "h1");
        reg.closed("x", "REST", "h1");
        assertEquals(2, cap.events.size());
        assertEquals(ConnState.CLOSED, cap.events.get(1).state());
    }

    @Test
    void resetClearsWithoutEvents() {
        var bus = newBus();
        var cap = new Capture(bus);
        var reg = new ConnectionRegistry(bus);
        reg.active("x", "REST", "h1");
        int before = cap.events.size();
        reg.reset();
        assertEquals(0, reg.counts().total());
        assertEquals(before, cap.events.size()); // reset is silent
        assertFalse(cap.events.isEmpty());
    }

    /** Fresh bus instance so tests don't cross-talk through the singleton. */
    private static EventBus newBus() {
        return new EventBus();
    }
}
