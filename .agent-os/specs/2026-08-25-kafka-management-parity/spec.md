# Spec — Kafka: AKHQ / Conduktor / Kafka UI parity and beyond

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §10.5 · **Prefix:** `KF-`

Audited against the source on 2026-08-25 (`KafkaService`, `KafkaExplorer`, `SchemaRegistryClient`,
`ConsumerLagCalculator`, `OffsetResetPlanner`, `ui.kafka`).

## Already built

| Capability | Where |
|------------|-------|
| Connect with arbitrary security props (SASL/SSL); topic list; describe | `KafkaService.connect/listTopics/describeAll` |
| Create / delete topic | `createTopic`, `deleteTopic` |
| Produce with key + value | `send` |
| Browse N messages (from beginning or end) and live consume | `browse`, `startConsuming` |
| Message filter, payload formatting, export JSON/CSV | `MessageFilter`, `PayloadFormatter`, `KafkaMessageExporter` |
| **Consumer group lag** per partition, with a heatmap | `ConsumerLagCalculator`, `ui.chart` |
| **Offset reset with preview-then-apply** (earliest/latest/timestamp) | `OffsetResetPlanner`, `applyOffsetReset` |
| Schema Registry: subjects, versions, register, compatibility get/set, **schema diff** | `SchemaRegistryClient`, `SchemaDiff` |
| JMX-less client metrics summary; code generation; object explorer | `KafkaMetricsSummary`, `KafkaCodeGenerator`, `KafkaExplorer` |
| `ConfigDiff` — a preview-then-apply diff engine for configs, **built but not yet wired to a writer** | `ConfigDiff` |

## Gaps — P1

- [ ] **KF-1 Topic configuration editing.** `ConfigDiff` computes the change set; nothing calls
      `incrementalAlterConfigs`. Wire it: view effective config (with defaults marked), edit, preview the
      diff, apply. Also broker-level config, read-only at first.
- [ ] **KF-2 Cluster / broker panel** — `describeCluster`: brokers, controller, rack, per-broker log dirs
      and sizes, API versions. There is no way to see the cluster itself today.
- [ ] **KF-3 Produce with headers, partition, timestamp and null (tombstone) values.** Today: key + value
      only. Tombstones matter for compacted topics and cannot be sent at all.
- [ ] **KF-4 Message headers on browse/consume** — rendered as a table per message, and filterable.
      `KafkaMessage` does not carry them yet.
- [ ] **KF-5 Serde support: Avro / Protobuf / JSON Schema** via the existing Schema Registry client, on
      both produce and consume, with the schema id resolved from the payload's magic byte. Without this the
      Schema Registry tab is informational only and Avro topics browse as mojibake.
- [ ] **KF-6 Consumer group administration** — describe members (client id, host, assignment), delete a
      group, delete offsets for a topic, and see whether a group is stable/rebalancing.
- [ ] **KF-7 Seek and replay** — consume from a timestamp or a specific partition/offset into the browser,
      and **re-produce selected messages to another topic** (the "replay this to the dev cluster" flow).

## Gaps — P2

- [ ] **KF-8 Partition management** — add partitions, view partition leadership/ISR/under-replicated state,
      and trigger a preferred-leader election.
- [ ] **KF-9 ACLs and quotas** — list/create/delete ACLs, view client quotas. Required in any managed cluster.
- [ ] **KF-10 `deleteRecords`** (truncate a partition up to an offset), with a typed confirmation.
- [ ] **KF-11 Kafka Connect management** — connector list, status, config, pause/resume/restart, task errors.
      This is what pushes Conduktor/AKHQ users away from lighter tools.
- [ ] **KF-12 Transactions and idempotence** in the producer (init/begin/commit/abort), plus reading with
      `read_committed` and showing aborted-transaction markers.
- [ ] **KF-13 Multi-cluster in one window** with the environment colour coding (`SQLX-2`), and a
      **topic/config diff between two clusters** for dev-vs-prod drift.
- [ ] **KF-14 Throughput and rate charts per topic/partition** over time; oldest-offset age; retention
      headroom ("this topic fills its retention in 3 days").
- [ ] **KF-15 Load generator** — produce at a rate with a payload template, measure end-to-end latency to a
      consumer, chart it.

## Gaps — P3

- [ ] **KF-16 ksqlDB / Streams topology viewer** (read-only first: list queries, show the topology graph).
- [ ] **KF-17 Message search across a whole topic** (bounded scan with a filter and a progress bar), not
      just the loaded page.
- [ ] **KF-18 MirrorMaker / replication status** where the cluster exposes it.

## Beyond AKHQ / Conduktor / Kafka UI

1. **Desktop, offline, no server to deploy.** AKHQ and Kafka UI are web apps someone must host; Conduktor's
   good parts are licensed. NexusLink is a local app on a locked-down network — the mission constraint again.
2. **Replay across protocols** — take a Kafka message and re-put it to IBM MQ, or POST it to a REST endpoint,
   from the same window (`KF-7` generalised). This is bridging/migration work that today needs custom code.
3. **Cluster drift diff** (`KF-13`) built on the same `ConfigDiff`/`SchemaDiff` engines already in the tree.
4. **Retention headroom and lag trends** (`KF-14`) — most tools show instantaneous lag; almost none show it
   as a trend with a projection.
5. **Governed destructive actions** — delete topic, `deleteRecords`, offset reset on a `prod`-tagged cluster
   inherit `SQLX-2`'s confirmation model.

## Build order

`KF-1` → `KF-4` → `KF-3` → `KF-5` → `KF-2` → `KF-6` → `KF-7` → then P2, starting with `KF-11`.
