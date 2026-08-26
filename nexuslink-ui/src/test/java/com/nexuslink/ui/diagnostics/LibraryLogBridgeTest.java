package com.nexuslink.ui.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LibraryLogBridgeTest {

    @AfterEach
    void cleanUp() {
        LibraryLogBridge.uninstall();
    }

    @Test
    void aLibraryWarningReachesTheActivityLog() {
        List<String> lines = new ArrayList<>();
        LibraryLogBridge.install(lines::add);
        assertTrue(LibraryLogBridge.isInstalled());

        Logger.getLogger("org.apache.kafka.clients.NetworkClient")
                .warning("Connection to node 1 could not be established");

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("could not be established"), lines.get(0));
        assertTrue(lines.get(0).startsWith("warning"), lines.get(0));
    }

    @Test
    void driverChatterBelowTheThresholdIsNotForwarded() {
        List<String> lines = new ArrayList<>();
        LibraryLogBridge.install(lines::add);
        Logger.getLogger("org.apache.kafka.clients.producer.ProducerConfig")
                .info("acks = all, batch.size = 16384");
        assertTrue(lines.isEmpty(), "INFO chatter would bury the user's own activity: " + lines);
    }

    @Test
    void aLowerThresholdLetsInfoThrough() {
        List<String> lines = new ArrayList<>();
        LibraryLogBridge.install(lines::add, Level.INFO);
        Logger.getLogger("com.mongodb.driver.cluster").info("Cluster description updated");
        assertEquals(1, lines.size());
    }

    @Test
    void uninstallingStopsTheForwarding() {
        List<String> lines = new ArrayList<>();
        LibraryLogBridge.install(lines::add);
        LibraryLogBridge.uninstall();
        assertFalse(LibraryLogBridge.isInstalled());
        Logger.getLogger("x.y.Z").warning("after uninstall");
        assertTrue(lines.isEmpty());
    }

    @Test
    void installingTwiceDoesNotDoubleUpTheMessages() {
        List<String> lines = new ArrayList<>();
        LibraryLogBridge.install(lines::add);
        LibraryLogBridge.install(lines::add);
        Logger.getLogger("x.y.Z").warning("once");
        assertEquals(1, lines.size(), lines.toString());
    }

    @Test
    void aNullActivityLogIsIgnoredRatherThanInstalled() {
        LibraryLogBridge.install(null);
        assertFalse(LibraryLogBridge.isInstalled());
    }

    @Test
    void theLineNamesTheLevelTheLoggerAndTheMessage() {
        LogRecord record = new LogRecord(Level.WARNING, "Broker not available");
        record.setLoggerName("org.apache.kafka.clients.NetworkClient");
        assertEquals("warning  o.a.k.c.NetworkClient  Broker not available",
                LibraryLogBridge.format(record));
    }

    @Test
    void anExceptionsOwnMessageIsAppendedBecauseItIsUsuallyTheInformativeOne() {
        LogRecord record = new LogRecord(Level.SEVERE, "Connection failed");
        record.setLoggerName("io.lettuce.core.RedisClient");
        record.setThrown(new java.net.ConnectException("Connection refused"));
        String line = LibraryLogBridge.format(record);
        assertTrue(line.contains("ConnectException: Connection refused"), line);
    }

    @Test
    void aLoggerNameIsShortenedToItsInitialsAndClass() {
        assertEquals("o.a.k.c.NetworkClient",
                LibraryLogBridge.shortName("org.apache.kafka.clients.NetworkClient"));
        assertEquals("Simple", LibraryLogBridge.shortName("Simple"));
    }
}
