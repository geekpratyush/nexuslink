# MQTT Client

Connect to an MQTT broker, subscribe to topic filters, and publish messages — built on
Eclipse Paho (MQTT v3.1 / v3.1.1).

## Connecting

1. Open **File ▸ New MQTT Client** (or pick the **HiveMQ** public-broker sample).
2. Enter a **broker URI**:
   - `tcp://host:1883` — plain TCP
   - `ssl://host:8883` — TLS
   - `ws://host:8000/mqtt` — WebSocket
3. Optionally set a **client id** (one is generated if blank) and a **username/password**
   for brokers that require authentication.
4. Click **Connect**. The button becomes **Disconnect** while the session is live; the status
   line turns green on success and reports a lost connection if the broker drops.

## Subscribing

Enter a **topic filter** and a **QoS** (0/1/2), then **Subscribe**. Wildcards are supported:

- `+` matches one level — `sensors/+/temp`
- `#` matches the rest of the tree — `sensors/#`

Incoming messages stream into the **Messages** log with their topic, QoS, and retained flag.
Use **Unsubscribe** to stop receiving a filter.

## Publishing

Set a **topic**, a **QoS**, an optional **Retained** flag, type a **payload**, and click
**Publish**. Retained messages are delivered to future subscribers immediately on subscribe.

> **Try it:** connect to the HiveMQ sample, subscribe to `nexuslink/#`, then publish to
> `nexuslink/hello` — your message appears in the log.

## Not yet supported

MQTT v5 features (user properties, message expiry, content type, correlation data) and a
persistent message history are on the roadmap. For other streaming/messaging needs, the
**Kafka** and **WebSocket** clients are also available.
