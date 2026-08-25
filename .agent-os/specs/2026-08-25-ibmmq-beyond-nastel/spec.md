# Spec — IBM MQ: MQ Explorer / Nastel AutoPilot parity and beyond

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §10.4 · **Prefix:** `MQ-`

Audited against the source on 2026-08-25 (`MqNativeService`, `MqConnectionProfile`, `Rfh2Header`,
`ui.ibmmq`). This is the thinnest client in the product relative to what its users expect.

## Already built

| Capability | Where |
|------------|-------|
| Native client connect: queue manager, channel, host/port, user/password, CCDT-less config | `MqConnectionProfile`, `MqNativeService.connect` |
| Put a message, with user properties (`usr` folder) | `put(queue, body, usrProperties)` |
| **RFH2 header parsing** on browse/get | `Rfh2Header` |
| Destructive get with timeout · browse first N · current depth | `get`, `browse`, `depth` |
| **Dead-letter queue browse** (resolves the QM's DLQ name) | `deadLetterQueueName`, `browseDeadLetterQueue` |
| UI: Put tab (with a property editor) and Browse & Get tab | `ui.ibmmq` |

Everything else an MQ administrator does daily is missing.

## Gaps — P1 (MQ Explorer's core; without these it is a toy)

- [ ] **MQ-1 Object explorer tree** — queue managers → queues (local/alias/remote/model) / topics /
      channels / listeners / subscriptions, with attributes in a details panel. Every other protocol in
      the product already has a `ResourceExplorerView`; MQ has none.
- [ ] **MQ-2 Full message browser.** Today: first N bodies. Needed: paged browse, the **full MQMD**
      (MsgId, CorrelId, Priority, Persistence, PutDate/Time, Expiry, BackoutCount, ReplyToQ/QMgr,
      Format, CodedCharSetId), per-message detail pane, hex/text/JSON/XML rendering, and browse **by
      MsgId / CorrelId / GroupId** rather than from the head only.
- [ ] **MQ-3 Message actions from the browser** — copy to another queue, **move** (get+put in one unit of
      work), delete a specific message, and **DLQ replay** (re-put to the original destination read from
      the dead-letter header, with a preview and a count). DLQ replay is the operation people buy tools for.
- [ ] **MQ-4 Queue administration** — create/alter/delete queues, set `MAXDEPTH`/`MAXMSGL`/triggering,
      **clear queue** (with a typed confirmation), and inhibit get/put. Via PCF.
- [ ] **MQ-5 PCF command layer.** `MQ-1`, `MQ-4`, `MQ-6`, `MQ-7` all sit on it: an internal
      `PcfService` wrapping `MQCFH`/PCF requests with typed responses, so each admin feature is a thin
      call rather than its own protocol work.
- [ ] **MQ-6 Queue and channel status** — `IPPROCS`/`OPPROCS`, open handles, uncommitted messages, oldest
      message age, channel state (running/retrying/stopped/inactive), start/stop/reset a channel.
- [ ] **MQ-7 Queue-manager status and statistics** — connection count, log usage, listener state.

## Gaps — P2 (the Nastel/monitoring end)

- [ ] **MQ-8 Depth history and alerting.** Sample depth per queue over time, chart it (`ui.chart` exists),
      and raise an in-app alert when depth or oldest-message-age crosses a threshold. This is the core of
      what AutoPilot sells; the desktop-scoped version is genuinely useful and nobody ships it free.
- [ ] **MQ-9 Topic / publish-subscribe support** — publish to a topic string, manage durable subscriptions,
      browse retained publications.
- [ ] **MQ-10 Transactional get with explicit commit/backout**, and a visible backout-threshold/BOQNAME
      path — so poison-message handling can be demonstrated instead of guessed at.
- [ ] **MQ-11 Load / soak tool** — put N messages of a given size/pattern at a rate, measure round-trip
      time to a reply queue, chart it. MQ Explorer has nothing like this.
- [ ] **MQ-12 Message payload transforms** — pretty-print and validate JSON/XML payloads, decode
      EBCDIC/CCSID correctly, and show the RFH2 `mcd`/`jms`/`usr` folders as structured tables (parsing
      exists; the rendering does not).
- [ ] **MQ-13 CCDT and TLS** — connect via a client channel definition table, and a full cipher-spec /
      keystore panel (the certificate manager already exists in `nexuslink-security`).
- [ ] **MQ-14 Multi-queue-manager view** — several QMs connected at once, with a combined queue list and
      cross-QM message move.

## Gaps — P3

- [ ] **MQ-15 Export/import queue definitions** as `runmqsc` script, and a **diff between two queue
      managers** (dev vs prod drift).
- [ ] **MQ-16 `runmqsc`-style command console** with completion over the object names from `MQ-1`.
- [ ] **MQ-17 Cluster view** — cluster queues, repositories, channel status per cluster.

## Beyond MQ Explorer and Nastel

1. **DLQ replay with preview** (`MQ-3`) — the single most-wanted MQ operation, and MQ Explorer makes you
   write a program for it.
2. **Depth trends and alerts on the desktop** (`MQ-8`) — AutoPilot-grade insight without a server install,
   an agent, or a licence.
3. **Queue-manager drift diff** (`MQ-15`) — "why does prod behave differently" answered in one screen.
4. **Same window as Kafka, JMS, Solace and RabbitMQ**, with one credential vault and one environment set.
   Migration and bridging work (MQ → Kafka) currently means two tools and a spreadsheet.
5. **Governed destructive actions** — clear-queue and delete-message inherit the SQL client's
   environment-governed execution (`SQLX-2`), so a prod QM cannot be cleared by muscle memory.

## Build order

`MQ-5` (the PCF layer, everything depends on it) → `MQ-1` → `MQ-2` → `MQ-3` → `MQ-4` → `MQ-6` → `MQ-7`
→ `MQ-8` → then P2. `MQ-12` can land any time; it is self-contained.
