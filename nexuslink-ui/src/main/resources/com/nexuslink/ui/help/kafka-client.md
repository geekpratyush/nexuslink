# Kafka Client

Browse topics, produce records, and consume a live stream.

## Connect
- Enter **bootstrap servers** (e.g. `host1:9092,host2:9092`).
- Choose a **security protocol**: `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, or `SASL_SSL`.
- For SASL, pick a mechanism (`PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`) and enter the username/password.
- **Connect** verifies the broker by listing topics.

## Browse
The left tree shows **topics → partitions**. A topic node shows partition count and replication factor; a partition node shows leader / replicas / in-sync replicas.

## Produce
On the **Produce** tab, pick a topic (or click one in the tree), enter an optional key + the value, and **Send**. The result shows the partition and offset the record landed at.

## Consume
On the **Consume** tab, enter a topic and optional group, choose *From beginning* if needed, and **Start consuming**. Records stream into the log (partition / offset / key / value). **Stop** ends the poll loop.

> Tip: there's a *Local Kafka* sample (`localhost:9092`) under **Samples (public)**.
