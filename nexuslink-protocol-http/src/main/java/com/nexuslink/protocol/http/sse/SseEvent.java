package com.nexuslink.protocol.http.sse;

/** One Server-Sent Event: its {@code event} type (default "message"), optional {@code id}, and data. */
public record SseEvent(String event, String id, String data) {
    public String typeOrDefault() { return event == null || event.isBlank() ? "message" : event; }
}
