package com.nexuslink.protocol.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The MQTT 5.0 message properties this client models as first-class fields, decoupled from the
 * Eclipse Paho types so both publish and received paths can carry them and they can be unit-tested
 * without a broker. Every field is optional; an unset field is {@code null} (or, for user
 * properties, the empty list).
 *
 * <p>Covered properties (MQTT 5.0 &sect;3.3.2.3, the PUBLISH properties):
 * <ul>
 *   <li><b>User properties</b> — an ordered list of arbitrary UTF-8 name/value pairs; duplicate
 *       names are permitted and order is preserved.</li>
 *   <li><b>Message Expiry Interval</b> — lifetime in <em>seconds</em> the server retains the message
 *       for still-to-connect subscribers; {@code null} means no expiry was set.</li>
 *   <li><b>Content Type</b> — a UTF-8 MIME-style description of the payload (e.g.
 *       {@code application/json}).</li>
 *   <li><b>Correlation Data</b> — opaque bytes a request/response requester uses to match a
 *       response to its request.</li>
 *   <li><b>Response Topic</b> — the topic a responder should publish the response to.</li>
 * </ul>
 *
 * <p>Instances are immutable: the user-property list and correlation-data array are defensively
 * copied on construction and on access, and {@link #equals(Object)}/{@link #hashCode()} compare the
 * correlation bytes by value.
 */
public record MqttMessageProperties(
        List<UserProperty> userProperties,
        Long messageExpiryInterval,
        String contentType,
        byte[] correlationData,
        String responseTopic) {

    /** A single MQTT 5.0 user property: an arbitrary UTF-8 name/value pair (both required). */
    public record UserProperty(String name, String value) {
        public UserProperty {
            Objects.requireNonNull(name, "user property name");
            Objects.requireNonNull(value, "user property value");
        }
    }

    /** A properties value with nothing set. */
    public static final MqttMessageProperties EMPTY =
            new MqttMessageProperties(List.of(), null, null, null, null);

    /** Canonical constructor: normalises {@code null} lists and defensively copies mutable inputs. */
    public MqttMessageProperties {
        userProperties = (userProperties == null)
                ? List.of()
                : List.copyOf(userProperties);
        correlationData = (correlationData == null) ? null : correlationData.clone();
    }

    /** The user-property list (never {@code null}; possibly empty, always immutable). */
    @Override
    public List<UserProperty> userProperties() {
        return userProperties;
    }

    /** The correlation-data bytes, or {@code null}; a defensive copy is returned. */
    @Override
    public byte[] correlationData() {
        return correlationData == null ? null : correlationData.clone();
    }

    /** {@code true} when no property at all is set (used to skip building Paho properties). */
    public boolean isEmpty() {
        return userProperties.isEmpty()
                && messageExpiryInterval == null
                && contentType == null
                && correlationData == null
                && responseTopic == null;
    }

    // --- fluent builders (return a new value, this record being immutable) ---------------------

    /** A copy with one more user property appended. */
    public MqttMessageProperties withUserProperty(String name, String value) {
        List<UserProperty> next = new ArrayList<>(userProperties);
        next.add(new UserProperty(name, value));
        return new MqttMessageProperties(next, messageExpiryInterval, contentType, correlationData, responseTopic);
    }

    /** A copy whose message-expiry interval (seconds) is set to {@code seconds} ({@code null} clears). */
    public MqttMessageProperties withMessageExpiryInterval(Long seconds) {
        return new MqttMessageProperties(userProperties, seconds, contentType, correlationData, responseTopic);
    }

    /** A copy whose content type is set to {@code contentType} ({@code null} clears). */
    public MqttMessageProperties withContentType(String contentType) {
        return new MqttMessageProperties(userProperties, messageExpiryInterval, contentType, correlationData, responseTopic);
    }

    /** A copy whose correlation data is set to {@code data} ({@code null} clears). */
    public MqttMessageProperties withCorrelationData(byte[] data) {
        return new MqttMessageProperties(userProperties, messageExpiryInterval, contentType, data, responseTopic);
    }

    /** A copy whose correlation data is set to the UTF-8 bytes of {@code data} ({@code null} clears). */
    public MqttMessageProperties withCorrelationData(String data) {
        return withCorrelationData(data == null ? null : data.getBytes(StandardCharsets.UTF_8));
    }

    /** A copy whose response topic is set to {@code responseTopic} ({@code null} clears). */
    public MqttMessageProperties withResponseTopic(String responseTopic) {
        return new MqttMessageProperties(userProperties, messageExpiryInterval, contentType, correlationData, responseTopic);
    }

    // --- value semantics (record defaults use identity for the byte[]) -------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MqttMessageProperties other)) {
            return false;
        }
        return userProperties.equals(other.userProperties)
                && Objects.equals(messageExpiryInterval, other.messageExpiryInterval)
                && Objects.equals(contentType, other.contentType)
                && Arrays.equals(correlationData, other.correlationData)
                && Objects.equals(responseTopic, other.responseTopic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userProperties, messageExpiryInterval, contentType,
                Arrays.hashCode(correlationData), responseTopic);
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (!userProperties.isEmpty()) {
            parts.add("userProperties=" + userProperties);
        }
        if (messageExpiryInterval != null) {
            parts.add("messageExpiryInterval=" + messageExpiryInterval + "s");
        }
        if (contentType != null) {
            parts.add("contentType=" + contentType);
        }
        if (correlationData != null) {
            parts.add("correlationData=" + new String(correlationData, StandardCharsets.UTF_8));
        }
        if (responseTopic != null) {
            parts.add("responseTopic=" + responseTopic);
        }
        return "MqttMessageProperties" + parts;
    }

    /** Convenience: an empty starting point (equal to {@link #EMPTY}) for the fluent {@code withX} builders. */
    public static MqttMessageProperties builder() {
        return EMPTY;
    }
}
