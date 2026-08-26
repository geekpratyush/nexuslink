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

- [x] **MG-1 Embedded `mongosh`-style shell.** *(2026-08-26)* Pure `MongoShellCommand` parses
      `db.<collection>.<op>(args).sort(…).limit(…).skip(…).count()` — nesting, string literals and
      top-level commas handled — plus the read-only database helpers (`getCollectionNames`, `stats`,
      `version`). `MongoService.runShell` maps a parsed command onto the driver (find/findOne/aggregate/
      distinct/count/insert/update/replace/delete/drop/index helpers/explain). UI: a **Shell tab** with
      ↑/↓ history and a printed transcript. It is a grammar parser, **not** a JS runtime, and says so
      when a line goes beyond it. 22 unit tests + `MongoShellLiveIT` (8 tests vs MongoDB 7). A REPL tab that evaluates `db.coll.find(...)` against the
      live connection with history, completion over real collection/field names, and printed results.
      This is the single biggest "it's not a real Mongo tool" gap. Build on the existing driver — a JS
      engine is *not* required if the shell parses the `db.<coll>.<op>(<json>)` grammar (covers >90% of
      real use); document that limit rather than pretending to be Node.
- [x] **MG-2 Tree / table / JSON result views.** *(2026-08-26)* Pure `BsonNode` turns a decoded
      document into expandable rows carrying the **real BSON type** (Int32/Int64/Double/Decimal128/
      Date/ObjectId/Binary/…), with dotted `path()` per node. UI: a **Tree view** (Field · Value · Type),
      now the default, beside the existing JSON/Table/Schema. `findDetailed` keeps the driver's decoded
      documents so types survive — the old path re-parsed its own printed JSON and lost them.
      11 tests. Compass's three-way toggle. Today the grid is one shape;
      documents with nested arrays are unreadable. A collapsible tree view is the default people expect.
- [x] **MG-3 In-place document editing in the tree** *(2026-08-26)* Pure `BsonValueParser` parses text
      as an explicitly chosen BSON type and refuses rather than coerces (an Int32 overflow says "it fits
      Int64, change the type"), then builds the `$set`/`$unset` on the field's path — so an edit touches
      one field of one `_id`, never the whole document. UI: double-click a tree value → type picker +
      value, with a warning when the type would change; plus Remove field (confirmed) and copy
      path/value. 11 tests. — type-aware (int32/int64/double/decimal/date/ObjectId/
      boolean/null), not a raw-JSON dialog. Wrong numeric type on save is the classic Mongo footgun.
- [x] **MG-4 Query bar with projection / sort / skip / limit fields** *(2026-08-26)* Pure
      `MongoQuerySpec` (normalised, paging helpers, `toShell()` rendering) drives
      `MongoService.findDetailed`. UI: a collapsible **Query options** bar — projection, sort, skip —
      beside the filter, with **Save query** favourites per collection. 8 tests. as first-class inputs beside the
      filter, plus a saved-query list per collection (favourites), matching Compass's bar.
- [x] **MG-5 Import / export a collection** *(2026-08-26)* Pure `CollectionTransfer`: JSON array,
      JSON lines and CSV, with nested fields as dotted columns on the way out and dotted headers
      rebuilding the nesting on the way in; CSV values are typed by what they look like (Int32/Int64 by
      size, Double, Boolean, ISO-8601 Date, JSON document/array, blank → null) because a string-only
      import leaves a collection unqueryable. `MongoService.exportCollection` **streams** to the file
      (a collection bigger than the heap still exports) with progress; `importDocuments` inserts in
      unordered batches. UI: **Export whole collection…** (format + filter) and **Import into
      collection…** with a CSV field-mapping table, type toggle and live first-document preview.
      14 unit tests + `MongoTransferLiveIT` (5 tests incl. a 250-document round trip). — JSON and CSV, field-mapped, with a preview and a progress
      bar; export the whole collection, not just the visible page. (CSV import machinery already exists
      for SQL in `CsvImportPlanner` — the mapping UI is reusable.)
- [x] **MG-6 Index usage and suggestions.** *(2026-08-26)* Pure `IndexAdvice`: reads `$indexStats`
      usage counters (with the counter start date, since "unused" on a freshly restarted server means
      nothing), flags drop candidates excluding `_id_`, and suggests the index a query wants —
      equality fields, then sort fields, then ranges — staying silent when an existing index's prefix
      already covers it. UI: an **Indexes view** beside the other result views. 10 unit tests +
      `MongoAnalysisLiveIT`. `$indexStats` per collection, an "unused index" flag, and the
      index the current query *would* want. Compass's Performance Insights, without the Atlas account.
- [x] **MG-7 Schema analyser panel** *(2026-08-26)* Pure `SchemaProfile` over a `$sample` of the
      collection: per-field presence %, BSON type distribution, null rate and distinct count, with
      nested documents flattened to dotted paths. Calls out the two findings that matter — a field
      *missing from some documents* and one stored as *mixed types*. UI: the Schema view now shows the
      real analysis with a summary line. 10 unit tests + live coverage. — sample N documents, show per-field type distribution, null rate and
      cardinality as a table + bars. The inference already exists for the diagram (`inferDiagram`); this is
      a second rendering of the same sample, not new sampling code.

## Gaps — P2 (heavy / operational users)

- [x] **MG-8 Aggregation pipeline stage-by-stage preview** *(2026-08-26)* Pure `PipelinePlan` builds the
      prefix, sample (`+ $limit`) and count (`+ $count`) queries per stage and validates each stage is a
      single `$operator`, naming the offending stage index; `StagePreview` reports the count, the delta
      from the previous stage and flags **the stage where the documents run out**. `previewPipeline`
      walks the stages, stopping at the first failure. UI: the pipeline builder gained a **Preview
      stages** button, a per-stage result list (failures red, the emptying stage amber) and the sample
      documents for the selected stage. 17 unit tests + live coverage. — output documents and a count after each stage,
      the feature Studio 3T's pipeline editor is actually bought for.
- [x] **MG-9 Change streams panel** *(2026-08-26)* `watchChanges` opens a change stream on a collection
      or the whole database on a daemon thread; `ChangeEvent` flattens each change into a log line
      (operation · namespace · `_id` · the *updated fields*, not the whole document). A standalone is
      refused up front with "change streams need a replica set", not a driver error. UI: a **Watch**
      tab with start/stop, a text filter and a bounded log; the watch stops with the tab.
      **Live-verified** against a new single-node replica-set fixture (`mongo-rs`, port 27018) —
      inserts/updates/deletes all arrive, and stopping the watch genuinely ends it. — watch a collection/database, stream inserts/updates/deletes into a
      bounded log with a filter, like the MQTT/Redis pub-sub panels already do.
- [x] **MG-10 `currentOp` / kill-op and the profiler** *(2026-08-26)* `currentOperations(minSeconds)`
      (longest first, with opid), `killOperation`, `setProfilingLevel`/`profilingStatus` and
      `slowOperations` reading `system.profile`. UI: **Server… ▸ Operations** (filter by duration, kill
      with a confirm) and **▸ Profiler** (level + slow-ms, slowest operations). Live-verified. — running operations, slow-query profile level
      control, and a slow-op table. No desktop client does this well.
- [x] **MG-11 Collection compare / sync between two collections** *(2026-08-26)* Pure `CollectionDiff`
      matches on `_id` and classifies each document (left-only / right-only / differing / identical),
      naming **which fields differ** for a differing pair (nested paths included) rather than just
      "not equal"; a document with no `_id` is reported, not dropped. `syncScript` generates the
      statements that would make the right side match the left — inserts and replaces, with **deletes
      emitted commented out**. UI: **Export ▸ Compare with another collection…** with the result table,
      a counts summary and the script (copy, or send to the Shell tab); nothing is ever applied.
      10 tests. _(Cross-connection compare is still same-database only.)_ (Studio 3T's Data Compare) — document diff
      by `_id`, then a generated apply script. Reuse the `DirectoryDiff` mental model from the file commander.
- [x] **MG-12 GridFS browser** *(2026-08-26)* `MongoService` gained the GridFS layer (buckets, list,
      streaming upload/download with progress, read-with-limit, rename and delete across revisions,
      drop bucket) and `GridFsFileSystem` adapts it to the existing `FileSystem`/`FileTransfer` seam —
      so the **whole two-pane commander** (transfer queue, drag-and-drop, quick-view, checksums) works
      on GridFS for free. UI: a **Files (GridFS)** tab. Compass shows GridFS only as raw `.files` /
      `.chunks` collections. 5 live tests incl. a multi-chunk round trip. — list buckets/files, upload, download, delete. The file commander's
      `FileSystem` seam already fits: a `GridFsFileSystem` gives the whole two-pane commander for free.
- [x] **MG-13 Replica-set / sharding status panel** *(2026-08-26)* `topologyStatus()` renders replica-set
      members with role, health and **seconds behind primary**, or a cluster's shards (flagging draining
      ones); a standalone says so rather than showing an empty table. UI: **Server… ▸ Topology**. — `rs.status()`, `sh.status()`, member lag and roles.
- [x] **MG-14 Bulk update/delete guardrail** *(2026-08-26)* `updateMany`/`deleteMany` now count first
      and confirm: "Update 12,431 document(s) in orders?" with the filter shown. An **unfiltered** write
      — the `deleteMany({})` that empties a collection — additionally requires typing the collection
      name before the button enables, because a dialog you can dismiss with Enter is not a guardrail.
      Nothing matching is reported without a dialog at all. — count-first, "this touches 12,431 documents", typed
      confirmation on a production-tagged connection. Shares the SQL client's governed-execution work
      (`SQLX-2`).
- [x] **MG-15 Explain for aggregate**, and a readable plan rendering *(2026-08-26)* Pure
      `ExplainSummary` walks the winning plan to its leaf and pulls out the plan stage, index name and
      key, documents/keys examined, documents returned and the **examined-per-returned ratio** — the
      whole diagnosis — plus a plain-language verdict ("Collection scan — every document was read…").
      The explain mode now shows that above the raw plan, and a new **explain aggregate** mode covers
      pipelines. 9 unit tests + live coverage.

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
