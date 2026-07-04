package com.nexuslink.plugin;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {

    /** A minimal connector stub carrying only an id + display name, enough to exercise the registry. */
    private static final class StubConnector implements ProtocolConnector {
        private final String id;
        StubConnector(String id) { this.id = id; }
        @Override public String protocolId() { return id; }
        @Override public String displayName() { return id == null ? "" : id.toUpperCase(); }
        @Override public PluginDescriptor descriptor() {
            return new PluginDescriptor(id, displayName(), "Test", "fa-plug", "#000000", id, "1.0");
        }
        @Override public ValidationResult validate(ConnectionConfig config) { return ValidationResult.ok(); }
        @Override public CompletableFuture<ConnectionResult> connect(ConnectionConfig c, ProgressCallback p) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public boolean isConnected() { return false; }
        @Override public void close() { }
    }

    @Test
    void indexesProvidersByIdInOrder() {
        ExtensionRegistry reg = ExtensionRegistry.fromProviders(
                List.of(new StubConnector("rest"), new StubConnector("kafka")));
        assertEquals(2, reg.size());
        assertEquals(List.of("rest", "kafka"), reg.ids());
        assertTrue(reg.contains("rest"));
        assertTrue(reg.find("kafka").isPresent());
        assertEquals("KAFKA", reg.find("kafka").get().displayName());
    }

    @Test
    void unknownIdIsEmpty() {
        ExtensionRegistry reg = ExtensionRegistry.fromProviders(List.of(new StubConnector("rest")));
        assertTrue(reg.find("nope").isEmpty());
        assertFalse(reg.contains("nope"));
    }

    @Test
    void skipsNullAndBlankIdProviders() {
        ExtensionRegistry reg = ExtensionRegistry.fromProviders(
                Arrays.asList(new StubConnector("rest"), null, new StubConnector("  "), new StubConnector(null)));
        assertEquals(1, reg.size());
        assertEquals(List.of("rest"), reg.ids());
    }

    @Test
    void firstProviderWinsOnDuplicateIdAndDuplicateIsRecorded() {
        StubConnector first = new StubConnector("rest");
        ExtensionRegistry reg = ExtensionRegistry.fromProviders(List.of(first, new StubConnector("rest")));
        assertEquals(1, reg.size());
        assertSame(first, reg.find("rest").orElseThrow());
        assertEquals(List.of("rest"), reg.duplicateIds());
    }

    @Test
    void descriptorsMatchProviders() {
        ExtensionRegistry reg = ExtensionRegistry.fromProviders(
                List.of(new StubConnector("rest"), new StubConnector("sftp")));
        List<PluginDescriptor> ds = reg.descriptors();
        assertEquals(List.of("rest", "sftp"), ds.stream().map(PluginDescriptor::protocolId).toList());
    }

    @Test
    void emptyProvidersYieldEmptyRegistry() {
        ExtensionRegistry reg = ExtensionRegistry.fromProviders(List.of());
        assertEquals(0, reg.size());
        assertTrue(reg.all().isEmpty());
        assertTrue(reg.duplicateIds().isEmpty());
    }

    @Test
    void realServiceLoaderPathReturnsARegistry() {
        // No providers are declared in this module's test classpath, so this is empty — but it proves
        // the ServiceLoader entry point runs without throwing.
        assertNotNull(ExtensionRegistry.load());
    }
}
