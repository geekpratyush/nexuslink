# Metrics Dashboard

The Metrics Dashboard (**Tools ▸ Metrics Dashboard…**) shows live request metrics gathered as you
use NexusLink's protocol tabs. It refreshes once a second and is read-only — traffic from the other
tabs feeds it automatically.

## What it shows

A per-**channel** table (a channel is a protocol or connection group, e.g. `REST`):

- **Requests / Errors / Error %** — lifetime totals for the channel.
- **P50 / P95 / P99** — latency percentiles (nearest-rank) over a rolling window of recent requests.
  P95 = "95% of requests were at least this fast"; watch P99 for tail latency.
- **Mean** — average latency over the channel's lifetime.
- **Throughput** — requests per second over the last 10 seconds.
- **Data** — total response bytes seen.

Below the table, a line chart plots **total requests/sec** (all channels) over the last 60 seconds.

## Tips

- **Reset** clears all counters and the chart — handy before a focused test run.
- Percentiles use the most recent samples per channel, so they reflect current behaviour rather than
  being dragged down by old data; the request/error counts remain exact for the whole session.
- Metrics live in memory only and are not persisted between runs.

## Not yet supported

Per-endpoint breakdowns, exportable reports, distributed-trace correlation, and alerting thresholds
are on the roadmap.
