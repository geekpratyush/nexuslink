# Spec — MongoDB client: Compass / Studio 3T / `mongosh` parity and beyond

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §10.1 · **Prefix:** `MG-`

Goal: someone who lives in Compass or Studio 3T should find nothing missing, and several things they
cannot get in either. Audited against the source on 2026-08-25 (`MongoService`, `MongoClientView`,
`MongoExplorer`, `MongoConnectionString`), not against session notes.

## Already built

| Capability | Where |
|------------|-------|
| Connect via connection string; **Auth panel** builds host/port/user/authSource/mechanism/TLS/SRV with a masked live preview | `MongoClientView.authDialog`, `MongoConnectionString` |
| Live users & roles — list, create, grant, drop | `MongoService.listUsers/createUser/grantRoles/dropUser` |
| find / aggregate / count / insertOne / updateMany / deleteMany, Extended-JSON in and out | `MongoService` |
| **Visual aggregation pipeline builder** (add/remove stages) | `MongoClientView` pipeline dialog |
| Object explorer: databases → collections → indexes, with `collStats` and index definitions | `MongoExplorer` |
| Create collection · create index · **drop index** (excludes `_id_`, confirms) | `MongoClientView` Structure menu |
| Edit / delete a single document from the result grid | `replaceById`, `deleteById` |
| `explain()` for a find | `MongoService.explain` |
| **Schema diagram inferred from sampled documents**, SVG/PNG export | `MongoService.inferDiagram` |
| **SQL over Mongo** (`executeSql`) — Studio 3T charges for this | `MongoService.executeSql` |
| Export result JSON / CSV; query history; `${VAR}` environments shared with every other protocol | `MongoClientView`, core |

## Gaps — P1 (a Compass/Studio 3T user notices these on day one)

- [ ] **MG-1 Embedded `mongosh`-style shell.** A REPL tab that evaluates `db.coll.find(...)` against the
      live connection with history, completion over real collection/field names, and printed results.
      This is the single biggest "it's not a real Mongo tool" gap. Build on the existing driver — a JS
      engine is *not* required if the shell parses the `db.<coll>.<op>(<json>)` grammar (covers >90% of
      real use); document that limit rather than pretending to be Node.
- [ ] **MG-2 Tree / table / JSON result views.** Compass's three-way toggle. Today the grid is one shape;
      documents with nested arrays are unreadable. A collapsible tree view is the default people expect.
- [ ] **MG-3 In-place document editing in the tree** — type-aware (int32/int64/double/decimal/date/ObjectId/
      boolean/null), not a raw-JSON dialog. Wrong numeric type on save is the classic Mongo footgun.
- [ ] **MG-4 Query bar with projection / sort / skip / limit fields** as first-class inputs beside the
      filter, plus a saved-query list per collection (favourites), matching Compass's bar.
- [ ] **MG-5 Import / export a collection** — JSON and CSV, field-mapped, with a preview and a progress
      bar; export the whole collection, not just the visible page. (CSV import machinery already exists
      for SQL in `CsvImportPlanner` — the mapping UI is reusable.)
- [ ] **MG-6 Index usage and suggestions.** `$indexStats` per collection, an "unused index" flag, and the
      index the current query *would* want. Compass's Performance Insights, without the Atlas account.
- [ ] **MG-7 Schema analyser panel** — sample N documents, show per-field type distribution, null rate and
      cardinality as a table + bars. The inference already exists for the diagram (`inferDiagram`); this is
      a second rendering of the same sample, not new sampling code.

## Gaps — P2 (heavy / operational users)

- [ ] **MG-8 Aggregation pipeline stage-by-stage preview** — output documents and a count after each stage,
      the feature Studio 3T's pipeline editor is actually bought for.
- [ ] **MG-9 Change streams panel** — watch a collection/database, stream inserts/updates/deletes into a
      bounded log with a filter, like the MQTT/Redis pub-sub panels already do.
- [ ] **MG-10 `currentOp` / kill-op and the profiler** — running operations, slow-query profile level
      control, and a slow-op table. No desktop client does this well.
- [ ] **MG-11 Collection compare / sync between two connections** (Studio 3T's Data Compare) — document diff
      by `_id`, then a generated apply script. Reuse the `DirectoryDiff` mental model from the file commander.
- [ ] **MG-12 GridFS browser** — list buckets/files, upload, download, delete. The file commander's
      `FileSystem` seam already fits: a `GridFsFileSystem` gives the whole two-pane commander for free.
- [ ] **MG-13 Replica-set / sharding status panel** — `rs.status()`, `sh.status()`, member lag and roles.
- [ ] **MG-14 Bulk update/delete guardrail** — count-first, "this touches 12,431 documents", typed
      confirmation on a production-tagged connection. Shares the SQL client's governed-execution work
      (`SQLX-2`).
- [ ] **MG-15 Explain for aggregate**, and a readable plan rendering (winning plan, index used, docs
      examined vs returned) instead of raw JSON.

## Gaps — P3

- [ ] **MG-16 Time-series and Atlas Search index management** (create/list search indexes where supported).
- [ ] **MG-17 Query performance drift** — keep timings per saved query, chart regressions (shares `SQLX-5`).
- [ ] **MG-18 Map-reduce / `$out` job runner** with progress.

## Beyond Compass and Studio 3T

1. **One tool, both models.** SQL over Mongo *and* real Mongo query syntax *and* the same `${VAR}`
   environments as the REST/Kafka/JDBC tabs — nobody else spans that.
2. **GridFS as a commander pane** (MG-12) — Compass has no file manager; NexusLink already has a good one.
3. **Governed execution on production connections** (MG-14) — Compass will happily let you `deleteMany({})`.
4. **Offline-first**: no Atlas login, no telemetry, works on an air-gapped network — the mission constraint
   is a feature here, since Compass's best diagnostics are cloud-gated.

## Build order

`MG-2` → `MG-3` → `MG-1` → `MG-4` → `MG-7` → `MG-5` → `MG-6` → then P2 in listed order.
Rationale: the result-view work (2, 3) is what makes every other feature usable; the shell (1) is the
loudest gap but lands better once results render properly.
