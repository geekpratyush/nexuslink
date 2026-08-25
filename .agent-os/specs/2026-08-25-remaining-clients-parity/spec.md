# Spec — the remaining clients: parity targets and gaps

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §10.6 · **Prefix:** `MSG-`

Mongo, SQL, file transfer, IBM MQ and Kafka have their own specs. This one covers every other client
so nothing in the product is unclaimed. Audited against the source on 2026-08-25 (tab structure and
service surfaces read directly from each view).

---

## JMS (`JmsView` — Send · Browse & Consume) — target: HermesJMS, and past it

- [ ] **MSG-J1 Destination explorer** — queues and topics from the provider where it exposes them, with
      depth, plus a saved destination list when it does not.
- [ ] **MSG-J2 Full JMS header/property panel** on browse (JMSMessageID, CorrelationID, Redelivered,
      DeliveryCount, Priority, Expiration, ReplyTo, Type) and typed property editing on send.
- [ ] **MSG-J3 Selector support** — browse and consume with a JMS selector expression, with syntax help.
- [ ] **MSG-J4 Message move / copy / DLQ replay** between destinations, in one transacted session
      (mirrors `MQ-3`; the same dialog should serve both).
- [ ] **MSG-J5 Bytes/Map/Object message types** on send and a correct rendering on browse. Text-only today.
- [ ] **MSG-J6 Transacted session controls** — commit/rollback, and a visible redelivery/backout path.

## Solace (`SolaceView` — Direct topics · Guaranteed queues)

- [ ] **MSG-S1 Queue browsing with message detail + selective delete/replay** (Solace's own admin UI is
      web-only and often not exposed to developers).
- [ ] **MSG-S2 Topic-endpoint and subscription management** — add/remove subscriptions on a queue.
- [ ] **MSG-S3 SEMP (v2) admin surface, read-only first** — VPN limits, queue quotas, client connections.
- [ ] **MSG-S4 Replay log support** where the broker has it (start replay from a timestamp).

## RabbitMQ (`RabbitMqView` — Messaging · Management)

- [ ] **MSG-R1 Full management-plugin coverage** — exchanges/bindings CRUD, policies, vhosts, users and
      permissions. Some of this exists in the Management tab; the object model is incomplete.
- [ ] **MSG-R2 Shovel / federation status**, and queue-level "move messages to another queue".
- [ ] **MSG-R3 Purge and delete guarded by environment tags** (`SQLX-2`).
- [ ] **MSG-R4 Message tracing** via firehose (`amq.rabbitmq.trace`) into a bounded log panel.

## MQTT (`MqttView`)

Built: v5 client, persistent message history with wildcard filters and payload search, activity log.

- [ ] **MSG-M1 Topic tree** — accumulate observed topics into a hierarchy with last value/retained flag per
      node (MQTT Explorer's core view, and the reason people use it over mosquitto_sub).
- [ ] **MSG-M2 Retained-message management** — see retained values and clear them (publish empty+retain).
- [ ] **MSG-M3 Payload decoding per topic** (JSON/protobuf-hex/image preview) with a remembered choice.
- [ ] **MSG-M4 Broker statistics** from `$SYS`, charted.

## Redis (`RedisView` — Console · Pub/Sub)

Built: console with completion, pub/sub panel, cluster + Sentinel topology (`RedisTopology`).

- [ ] **MSG-RD1 Key browser** — SCAN-based tree by `:` separator, with type icons, TTL, memory usage, and
      a type-aware value editor (string/hash/list/set/zset/stream/JSON). This is RedisInsight's main screen
      and the biggest gap in this client.
- [ ] **MSG-RD2 Stream support** — read/consume groups, pending entries, claim.
- [ ] **MSG-RD3 Server panel** — INFO sections charted (memory, hit rate, ops/sec, clients), SLOWLOG,
      and `CLIENT LIST` with kill.
- [ ] **MSG-RD4 Import/export keys** (RDB-less: SCAN + DUMP/RESTORE) between two Redis connections.

## LDAP (`LdapView` — List · Tree DIT)

Built: search/list, DIT tree, StartTLS + LDAPS.

- [ ] **MSG-L1 Entry editor** — add/modify/delete attributes with schema-aware types, and add/delete entries.
- [ ] **MSG-L2 LDIF import/export** of a subtree, with a preview.
- [ ] **MSG-L3 Schema browser** — objectClasses and attributeTypes from `cn=subschema`.
- [ ] **MSG-L4 Group membership editor** and a "who am I / effective rights" panel; password reset flow.

## SSH terminal (`ui.terminal`)

- [ ] **MSG-SSH1 Multi-session tabs with saved sessions** shared with the SFTP site manager (`FX-2`).
- [ ] **MSG-SSH2 Port forwarding** (local/remote/dynamic) with a status panel — the missing piece that
      makes PuTTY still necessary.
- [ ] **MSG-SSH3 Command snippets / broadcast to multiple sessions.**

## SNMP (`SnmpView` — Browser · Traps)

Built: v1/v2c/v3 with USM auth+priv on the wire, walk, trap receiver.

- [ ] **MSG-SN1 MIB loading and name resolution** — compile/load MIB files so OIDs render as names; today
      it is numeric OIDs only, which is the difference between usable and not.
- [ ] **MSG-SN2 SET operations** with type selection, and a table (conceptual row) view for tabular OIDs.
- [ ] **MSG-SN3 Polling + charts** for selected OIDs, with threshold alerts (shares `MQ-8`'s engine).

## Cloud messaging (SQS/SNS, Pub/Sub, Service Bus, Event Hubs)

- [ ] **MSG-C1 Dead-letter handling** for each — browse the DLQ, replay to the source, with a preview
      (one shared dialog with `MQ-3` and `MSG-J4`).
- [ ] **MSG-C2 Subscription/filter management** (SNS filter policies, Service Bus rules, Pub/Sub push
      configs), read-then-edit.
- [ ] **MSG-C3 Batch send with a payload template + rate**, and message-attribute editing.

## gRPC / GraphQL / WebSocket / SSE

- [ ] **MSG-G1 gRPC reflection-driven request builder** — generate a sample JSON request from the message
      descriptor instead of typing it blind; save requests into the REST collections tree (`CollectionNode`
      is protocol-neutral enough to hold them).
- [ ] **MSG-G2 GraphQL schema explorer + docs pane** and query completion from the introspected schema.
- [ ] **MSG-G3 Save WebSocket/SSE/gRPC/GraphQL requests into collections** — one saved-request model across
      every protocol, which no API client does (Postman is HTTP-first).

---

## The through-line

Five capabilities recur across every client above, and each should be built **once**:

1. **DLQ / replay dialog** — `MQ-3`, `MSG-J4`, `MSG-C1`, `KF-7`.
2. **Environment-governed destructive actions** — `SQLX-2`, used by MQ, Kafka, Redis, RabbitMQ, Mongo.
3. **Sampling + charting + threshold alerts** — `MQ-8`, `KF-14`, `MSG-RD3`, `MSG-M4`, `MSG-SN3`.
4. **Saved-request/session tree with folders** — `CollectionNode` (REST, shipped), `FX-2`, `MSG-G3`.
5. **Diff-then-apply for configuration** — `ConfigDiff`/`SchemaDiff` (Kafka), `MQ-15`, `MSG-R1`.

Building the shared piece first is what keeps thirteen protocol clients from becoming thirteen codebases.
