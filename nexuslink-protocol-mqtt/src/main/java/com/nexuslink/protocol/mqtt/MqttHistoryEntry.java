package com.nexuslink.protocol.mqtt;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of MQTT message history: a message that was received from, or published to, a broker.
 *
 * <p>The record is Paho-free and carries only what a history view needs — the wall-clock instant,
 * which way the message travelled, the topic it landed on, its QoS and retained flag, and the
 * payload as text. {@link #encode()} / {@link #decode(String)} are the on-disk form used by
 * {@link MqttHistoryStore}: a tab-separated, {@code key=value} line so an older reader survives a
 * newer writer adding fields (unknown keys are ignored, unparsable lines are skipped).
 */
public record MqttHistoryEntry(long epochMillis, Direction direction, String topic,
                               int qos, boolean retained, String payload) {

    /** Which way the message travelled relative to this client. */
    public enum Direction {
        /** Delivered to a subscription on this client. */
        RECEIVED,
        /** Published by this client. */
        PUBLISHED;

        static Direction fromWire(String token) {
            return "PUBLISHED".equalsIgnoreCase(token) ? PUBLISHED : RECEIVED;
        }
    }

    public MqttHistoryEntry {
        Objects.requireNonNull(direction, "direction");
        topic = topic == null ? "" : topic;
        payload = payload == null ? "" : payload;
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS must be 0, 1 or 2 but was " + qos);
        }
    }

    /** A received message, stamped now. */
    public static MqttHistoryEntry received(String topic, String payload, int qos, boolean retained) {
        return new MqttHistoryEntry(System.currentTimeMillis(), Direction.RECEIVED, topic, qos, retained, payload);
    }

    /** A published message, stamped now. */
    public static MqttHistoryEntry published(String topic, String payload, int qos, boolean retained) {
        return new MqttHistoryEntry(System.currentTimeMillis(), Direction.PUBLISHED, topic, qos, retained, payload);
    }

    /** The timestamp as an {@link Instant}. */
    public Instant timestamp() {
        return Instant.ofEpochMilli(epochMillis);
    }

    // ------------------------------------------------------------------ line format

    /**
     * Serialises this entry to a single line — no tabs or newlines survive into the output, so one
     * entry always occupies exactly one line however odd its payload is.
     */
    public String encode() {
        return "ts=" + epochMillis
                + "\tdir=" + direction
                + "\tqos=" + qos
                + "\tretained=" + retained
                + "\ttopic=" + escape(topic)
                + "\tpayload=" + escape(payload);
    }

    /**
     * Parses a line written by {@link #encode()}, or returns {@code null} if the line is blank or
     * carries no topic — a hand-edited or truncated file degrades entry-by-entry rather than failing.
     */
    public static MqttHistoryEntry decode(String line) {
        if (line == null || line.isBlank()) return null;
        long ts = 0;
        Direction dir = Direction.RECEIVED;
        int qos = 0;
        boolean retained = false;
        String topic = null;
        String payload = "";
        for (String field : line.split("\t", -1)) {
            int eq = field.indexOf('=');
            if (eq < 0) continue;
            String key = field.substring(0, eq);
            String value = field.substring(eq + 1);
            switch (key) {
                case "ts" -> ts = parseLong(value);
                case "dir" -> dir = Direction.fromWire(value);
                case "qos" -> qos = clampQos(parseLong(value));
                case "retained" -> retained = Boolean.parseBoolean(value);
                case "topic" -> topic = unescape(value);
                case "payload" -> payload = unescape(value);
                default -> { /* a field from a newer writer — ignore it */ }
            }
        }
        return topic == null ? null : new MqttHistoryEntry(ts, dir, topic, qos, retained, payload);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clampQos(long value) {
        return (int) Math.max(0, Math.min(2, value));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i == s.length() - 1) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'n' -> out.append('\n');
                case '\\' -> out.append('\\');
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }
}
