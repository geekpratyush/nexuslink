# MQTT Client

> **Status:** on the roadmap (Phase 5.4).

The planned MQTT client (Eclipse Paho, v3.1.1 + v5.0) will support:

- Broker URL, client id, and QoS 0/1/2.
- Subscribe / publish with a live message history (timestamp, QoS, retained flag).
- MQTT v5 properties: user properties, message expiry, content type, correlation data.

In the meantime, **Kafka** and **WebSocket** clients are available for streaming/messaging.
