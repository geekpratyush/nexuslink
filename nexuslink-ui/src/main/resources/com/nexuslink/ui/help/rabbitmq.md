# RabbitMQ Client

Connect to a RabbitMQ broker, declare exchanges/queues/bindings, publish messages, and consume
deliveries — built on the official Java `amqp-client` (AMQP 0.9.1).

## Connecting

1. Open **File ▸ New RabbitMQ Client** (or pick a saved RabbitMQ connection).
2. Enter a **broker URI** or host:
   - `amqp://host:5672` — plain AMQP
   - `amqps://host:5671` — AMQP over TLS
   - `host` or `host:port` — bare form (defaults to port 5672)
   - Credentials may be embedded: `amqp://user:pass@host/vhost` (these win over the fields).
3. Set a **username/password** (RabbitMQ defaults to `guest`/`guest`, usable only from localhost).
4. Click **Connect**. The button becomes **Disconnect** while the session is live.

## Topology — exchanges, queues, bindings

- **Declare exchange** — name it, pick a type (`direct`, `fanout`, `topic`, `headers`), and
  toggle **Durable** (survives a broker restart).
- **Declare queue** — name it and toggle **Durable**. Idempotent: re-declaring an existing
  queue/exchange with the same settings is a no-op.
- **Bind** — links the named queue to the named exchange with a **routing key**. For `topic`
  exchanges the key may contain wildcards (`*` one word, `#` zero or more).

## Publishing

Set a **routing key** (and an **exchange**, or leave it blank to use the default exchange, where
the routing key is the target queue name), type a **payload**, and click **Publish**. Messages
are sent persistent (`delivery_mode=2`).

## Consuming

Enter a **queue** and click **Consume** — deliveries stream into the **Messages** log with their
exchange and routing key, and are acknowledged automatically. Click **Stop** to cancel.

> **Try it (local Docker):** `docker run -p 5672:5672 -p 15672:15672 rabbitmq:3-management`,
> connect to `amqp://localhost:5672`, declare a `direct` exchange `demo` and a queue `inbox`,
> bind them with key `hello`, consume `inbox`, then publish to `demo` with key `hello`.

## Environment variables

Every field resolves `${VAR}` placeholders against the active environment at connect / declare /
publish / consume time, so you can keep the broker URL and credentials out of the UI — e.g.
`amqp://${RABBIT_USER}:${RABBIT_PASS}@${RABBIT_HOST}/`. See **Environment Variables**.

## Not yet supported

Publisher confirms, manual per-message ack/nack/requeue controls, message headers/properties
editing, and the management REST API (queue depths, connections) are on the roadmap. For other
streaming/messaging needs, the **Kafka** and **MQTT** clients are also available.
