package com.nexuslink.ui.files;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class TransferErrorsTest {

    @Test
    void networkTimeoutIsTransient() {
        assertTrue(TransferErrors.isTransient(new SocketTimeoutException("read timed out")));
        assertTrue(TransferErrors.isTransient(new ConnectException("Connection refused")));
    }

    @Test
    void messageKeywordsAreTransient() {
        assertTrue(TransferErrors.isTransient(new RuntimeException("Connection reset by peer")));
        assertTrue(TransferErrors.isTransient(new RuntimeException("Broken pipe")));
        assertTrue(TransferErrors.isTransient(new java.io.IOException("Network is unreachable")));
    }

    @Test
    void transientCauseIsDetectedThroughTheChain() {
        Exception wrapped = new RuntimeException("upload failed", new SocketTimeoutException("timed out"));
        assertTrue(TransferErrors.isTransient(wrapped));
    }

    @Test
    void permanentErrorsAreNotTransient() {
        assertFalse(TransferErrors.isTransient(new java.nio.file.NoSuchFileException("/x")));
        assertFalse(TransferErrors.isTransient(new java.nio.file.AccessDeniedException("/x")));
        assertFalse(TransferErrors.isTransient(new UnknownHostException("bad.host")));
        assertFalse(TransferErrors.isTransient(new RuntimeException("permission denied")));
    }

    @Test
    void nullIsNotTransient() {
        assertFalse(TransferErrors.isTransient(null));
    }
}
