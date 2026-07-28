package com.nexuslink.plugin.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeGenRegistryTest {

    /** A generator that claims one request type and echoes the target it was asked for. */
    private static final class FakeGenerator implements CodeGenerator {
        private final String id;
        private final Class<?> accepts;

        FakeGenerator(String id, Class<?> accepts) {
            this.id = id;
            this.accepts = accepts;
        }

        @Override public String protocolId() { return id; }
        @Override public String displayName() { return id.toUpperCase(); }
        @Override public boolean supports(Object request) { return accepts.isInstance(request); }
        @Override public List<CodeGenTarget> targets() {
            return List.of(CodeGenTarget.of("cli", "CLI"), CodeGenTarget.of("java", "Java"));
        }
        @Override public String generate(CodeGenTarget target, Object request) {
            return id + ":" + target.id() + ":" + request;
        }
    }

    @Test
    @DisplayName("providers are indexed by protocol id, in registration order")
    void indexesProviders() {
        CodeGenRegistry registry = CodeGenRegistry.fromProviders(
                List.of(new FakeGenerator("rest", String.class), new FakeGenerator("kafka", Integer.class)));

        assertEquals(2, registry.size());
        assertEquals(List.of("rest", "kafka"),
                registry.generators().stream().map(CodeGenerator::protocolId).toList());
        assertTrue(registry.byProtocol("kafka").isPresent());
        assertTrue(registry.byProtocol("nope").isEmpty());
    }

    @Test
    @DisplayName("only the generators that support the request are offered")
    void generatorsForRequest() {
        CodeGenRegistry registry = CodeGenRegistry.fromProviders(
                List.of(new FakeGenerator("rest", String.class), new FakeGenerator("kafka", Integer.class)));

        assertEquals(List.of("kafka"),
                registry.generatorsFor(42).stream().map(CodeGenerator::protocolId).toList());
        assertEquals("rest", registry.firstFor("a request").orElseThrow().protocolId());
        assertTrue(registry.generatorsFor(null).isEmpty(), "a null request offers nothing");
        assertTrue(registry.generatorsFor(3.14).isEmpty(), "an unclaimed type offers nothing");
    }

    @Test
    @DisplayName("a duplicate protocol id is skipped and reported, not thrown")
    void duplicateIdsAreReported() {
        CodeGenRegistry registry = CodeGenRegistry.fromProviders(List.of(
                new FakeGenerator("rest", String.class),
                new FakeGenerator("rest", Integer.class)));

        assertEquals(1, registry.size(), "the first registration wins");
        assertEquals(List.of("rest"), registry.duplicateProtocolIds());
        assertTrue(registry.generatorsFor(42).isEmpty(), "the shadowed generator is not consulted");
    }

    @Test
    @DisplayName("a blank protocol id is ignored")
    void blankIdIgnored() {
        CodeGenRegistry registry = CodeGenRegistry.fromProviders(
                List.of(new FakeGenerator("  ", String.class)));

        assertEquals(0, registry.size());
        assertTrue(registry.duplicateProtocolIds().isEmpty());
    }

    @Test
    @DisplayName("targetById falls back to the first target for an unknown id")
    void targetLookup() {
        CodeGenerator generator = new FakeGenerator("rest", String.class);

        assertEquals("java", generator.targetById("java").id());
        assertEquals("cli", generator.targetById("does-not-exist").id());
    }

    @Test
    @DisplayName("a target normalises a blank label and syntax, and rejects a blank id")
    void targetNormalisation() {
        CodeGenTarget target = new CodeGenTarget("go", "  ", "");

        assertEquals("go", target.label());
        assertEquals("text", target.syntax());
        assertThrows(IllegalArgumentException.class, () -> new CodeGenTarget(" ", "x", "y"));
    }
}
