# Kafka Client

Connect with a bootstrap server list (`localhost:9092`, or several comma-separated). Security —
`SASL_SSL`, SCRAM, PLAIN, TLS truststore/keystore — is set in the connection bar's security panel.

## Produce

Topic, key and value, plus everything a real producer needs:

- **Partition** — leave blank to let the partitioner choose, or aim a record at one partition.
- **Timestamp (epoch ms)** — blank means "now"; set it when the target is time-indexed.
- **Headers** — one `name: value` per line in the collapsible headers editor. Kafka allows a header
  name to repeat, and so does this.
- **Tombstone (null value)** — sends a **null** value against the key, which is what deletes that key
  on a compacted topic. An empty string is not a tombstone; the value editor is disabled while the
  toggle is on so the two cannot be confused. A tombstone without a key is refused, because it would
  delete nothing.

## Consume

- **Start/Stop** consuming with a group, or **Browse** — a read with **no consumer-group side
  effects**: no group join, no commits, no rebalance for anyone else.
- **Format** renders keys and values as String / JSON / Hex / Base64.
- **Decode with Schema Registry** reads the schema id from each payload's five-byte header, fetches
  that schema, and renders **Avro** as JSON (and JSON Schema payloads as their JSON). Without it an
  Avro topic browses as mojibake, because Avro binary carries no field names. Protobuf is identified
  but needs its compiled descriptor to decode, and says so.
- **Headers** appear in their own column; **Show headers…** on a row shows the record in full.
- A **tombstone** is labelled *(tombstone — null value)* rather than shown as an empty record.
- **Replay selected to another topic…** re-produces the chosen records elsewhere — the "replay this
  to the dev cluster" flow. Keys, headers and tombstones are carried across; partitions are not,
  because the target may be partitioned differently. Timestamps are optional.
- Filters: key/value contains or regex, partition, case sensitivity — and header-aware.
- **Export JSON… / CSV…** writes the selected rows, or all of them.

## Consumer Lag

Per-partition lag for a group: current offset, end offset, lag, and the member holding each
partition. **Reset offsets…** previews the plan (earliest / latest / a timestamp / a specific
offset) before applying it.

## Groups

Members with their client id, host and partition assignment, plus the group's state (`Stable`,
`PreparingRebalance`, `Empty`). Two destructive actions, both confirmed:

- **Delete offsets for topic…** — the group re-reads that topic per its `auto.offset.reset`.
- **Delete group…** — the group and its offsets go.

Kafka refuses either while consumers are still connected; the panel says which state the group is in
and that its consumers must stop first, rather than passing the raw error through.

## Cluster

Brokers with host, port, rack, and which one is the **controller**, plus the cluster id.

## Topic Config

The effective configuration of a topic with **defaults marked** and read-only settings flagged —
you cannot judge `retention.ms` without knowing whether it is a broker default or something someone
set. Type changes as `name=value` lines (a name with no value **deletes the override**, reverting to
the default), press **Preview changes** to see exactly what would happen, and apply.

Changes go through `incrementalAlterConfigs`, which changes only what you named — the older
`alterConfigs` call replaces the whole set and silently resets anything you did not send.

## Schema Registry

Subjects, versions, and compatibility level, against a Confluent-compatible registry (including
Apicurio's ccompat API). The same registry powers **Decode with Schema Registry** on the Consume tab.

## Topics and the object tree

The explorer lists topics with partitions, replicas and ISR. Create and delete topics from the
toolbar; deletion is confirmed.

See also: **Menus & Toolbars**, **Security & Authentication**.
