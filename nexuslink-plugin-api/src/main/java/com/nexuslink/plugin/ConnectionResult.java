package com.nexuslink.plugin;

import java.util.Optional;

public record ConnectionResult(
        boolean success,
        String message,
        String errorCode,       // null on success; used to link to help topic
        Throwable cause
) {
    public static ConnectionResult success(String message) {
        return new ConnectionResult(true, message, null, null);
    }

    public static ConnectionResult failure(String message, String errorCode, Throwable cause) {
        return new ConnectionResult(false, message, errorCode, cause);
    }

    public Optional<Throwable> getCause() {
        return Optional.ofNullable(cause);
    }
}
