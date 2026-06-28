package com.nexuslink.protocol.rabbitmq;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure dead-letter queue-argument builder. */
class DeadLetterArgsTest {

    @Test
    void buildsAllArguments() {
        Map<String, Object> args = DeadLetterArgs.builder()
                .deadLetterExchange("dlx")
                .deadLetterRoutingKey("dead")
                .messageTtl(60000)
                .maxLength(1000)
                .overflow("reject-publish")
                .build();

        assertEquals("dlx", args.get("x-dead-letter-exchange"));
        assertEquals("dead", args.get("x-dead-letter-routing-key"));
        assertEquals(60000L, args.get("x-message-ttl"));
        assertEquals(1000L, args.get("x-max-length"));
        assertEquals("reject-publish", args.get("x-overflow"));
        assertEquals(5, args.size());
    }

    @Test
    void minimalBuildOnlyEmitsExchange() {
        Map<String, Object> args = DeadLetterArgs.builder()
                .deadLetterExchange("dlx")
                .build();
        assertEquals(Map.of("x-dead-letter-exchange", "dlx"), args);
    }

    @Test
    void optionalFieldsOmittedWhenUnset() {
        Map<String, Object> args = DeadLetterArgs.builder()
                .deadLetterExchange("dlx")
                .messageTtl(5000)
                .build();
        assertEquals(2, args.size());
        assertTrue(args.containsKey("x-message-ttl"));
        assertFalse(args.containsKey("x-dead-letter-routing-key"));
        assertFalse(args.containsKey("x-max-length"));
        assertFalse(args.containsKey("x-overflow"));
    }

    @Test
    void argumentsAreInsertionOrdered() {
        Map<String, Object> args = DeadLetterArgs.builder()
                .deadLetterExchange("dlx")
                .deadLetterRoutingKey("dead")
                .messageTtl(1000)
                .build();
        assertEquals(
                java.util.List.of("x-dead-letter-exchange", "x-dead-letter-routing-key", "x-message-ttl"),
                new ArrayList<>(args.keySet()));
    }

    @Test
    void blankRoutingKeyIsNotEmitted() {
        Map<String, Object> args = DeadLetterArgs.builder()
                .deadLetterExchange("dlx")
                .deadLetterRoutingKey("")
                .build();
        assertFalse(args.containsKey("x-dead-letter-routing-key"));
    }

    @Test
    void exchangeIsRequired() {
        assertThrows(IllegalStateException.class, () -> DeadLetterArgs.builder().build());
        assertThrows(IllegalStateException.class,
                () -> DeadLetterArgs.builder().deadLetterExchange("  ").build());
    }

    @Test
    void negativeTtlAndMaxLengthRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DeadLetterArgs.builder().messageTtl(-1));
        assertThrows(IllegalArgumentException.class,
                () -> DeadLetterArgs.builder().maxLength(-5));
    }

    @Test
    void zeroTtlIsAllowed() {
        Map<String, Object> args = DeadLetterArgs.builder()
                .deadLetterExchange("dlx")
                .messageTtl(0)
                .build();
        assertEquals(0L, args.get("x-message-ttl"));
    }
}
