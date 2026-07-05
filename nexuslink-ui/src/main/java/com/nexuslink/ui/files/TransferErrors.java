package com.nexuslink.ui.files;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Classifies a transfer failure as <em>transient</em> (worth an automatic retry — a dropped
 * connection, a timeout, a temporarily unreachable host) or permanent (bad credentials, missing
 * file, no permission — retrying won't help). Pure and JavaFX-free.
 */
public final class TransferErrors {

    private TransferErrors() {}

    /** True when {@code error} (or any cause in its chain) looks like a retriable network hiccup. */
    public static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (isTransientType(t) || messageLooksTransient(t.getMessage())) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static boolean isTransientType(Throwable t) {
        // A timeout / reset / unreachable-host is transient; an unknown host usually is not.
        return t instanceof SocketTimeoutException
                || t instanceof ConnectException
                || t instanceof NoRouteToHostException
                || (t instanceof SocketException && !(t instanceof UnknownHostException));
    }

    private static boolean messageLooksTransient(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("timed out") || m.contains("timeout")
                || m.contains("connection reset") || m.contains("connection refused")
                || m.contains("broken pipe") || m.contains("connection closed")
                || m.contains("temporarily unavailable") || m.contains("try again")
                || m.contains("network is unreachable") || m.contains("no route to host");
    }
}
