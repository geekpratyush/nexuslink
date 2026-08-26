# MongoDB client

Connect with a `mongodb://` or `mongodb+srv://` string. The connection is shown as a compact chip —
double-click it to edit, which also keeps any password in it off the screen.

The status line names what you are actually connected to: product, version and topology
(`MongoDB 7.0.5 · replica set`). That matters because a **standalone** server has no oplog, so it
cannot run transactions or change streams whatever its version, and the wire-compatible imitations
(Amazon DocumentDB, Azure Cosmos DB, FerretDB) report a MongoDB version they do not fully implement.

---

## The query bar

**Operation** chooses find / sql / aggregate / explain / explain aggregate / insertOne / updateMany /
deleteMany. The editor below takes Extended JSON; **Ctrl+Enter** runs it.

**Query options** (the collapsible bar) adds the rest of what Compass's bar has:

| Field | Example |
|---|---|
| Projection | `{"name": 1, "_id": 0}` |
| Sort | `{"name": -1}` |
| Skip | `20` |
| limit | in the toolbar |

**Save query** keeps the current filter/projection/sort as a favourite of that collection; the
**Saved** menu loads one back and runs it.

---

## Reading results

**View:** switches between five renderings of the same result.

- **Tree** (the default) — one expandable row per field with its **BSON type** beside the value.
  Nested documents and arrays expand in place; this is the only view where `{"a": {"b": [1,2]}}` is
  readable.
- **JSON** — pretty-printed shell JSON.
- **Table** — top-level fields as columns.
- **Schema** — samples the collection with `$sample` and reports, per field: how often it is present,
  the **type distribution**, the null rate and the distinct count. It calls out the two things that
  actually bite: a field **missing from some documents** (queries silently skip those) and one stored
  as **mixed types** (comparisons and sorts behave differently per document).
- **Indexes** — `$indexStats` usage per index with the date the counters started, unused indexes
  flagged as drop candidates, and the index the current query *would* want when nothing existing
  covers it.

### Editing a document in place

Right-click a value in the Tree view ▸ **Edit value…** (or double-click it). The dialog asks for the
**type** as well as the value, and warns when saving would change it — because in MongoDB the
difference between `1` as an Int32, an Int64 and a Double is real, and a JSON textbox hides it. The
save is a `$set` on that field's path for that `_id` alone, so a concurrent edit to another field is
not reverted. **Remove field…** issues `$unset` the same way.

---

## Shell tab

Runs `mongosh`-style lines against the live connection:

```
db.people.find({"role": "admin"}).sort({"name": 1}).limit(10)
db.orders.aggregate([{"$group": {"_id": "$status", "n": {"$sum": 1}}}])
db.people.updateMany({"age": {"$lt": 40}}, {"$set": {"junior": true}})
db.getCollectionNames()
```

**↑ / ↓** walk the history. Supported: `find`, `findOne`, `aggregate`, `distinct`, `count`,
`countDocuments`, `insertOne`, `insertMany`, `updateOne`, `updateMany`, `replaceOne`, `deleteOne`,
`deleteMany`, `drop`, `createIndex`, `dropIndex`, `getIndexes`, `stats`, `explain`, and the
database-level `getCollectionNames`, `getCollectionInfos`, `stats`, `version` — with `.sort()`,
`.limit()`, `.skip()`, `.count()` and `.pretty()` chained on.

**It parses the command shape; it is not a JavaScript engine.** A loop or a variable assignment is
refused with a sentence saying so, rather than half-running.

---

## Watch tab (change streams)

Watch a collection or the whole database and log inserts, updates, replaces and deletes as they
happen. An update shows the **fields that changed**, not the whole document. A text filter keeps only
matching lines, and the log is bounded so an active collection cannot fill the pane unattended.

Change streams need an oplog, so on a standalone server the tab says that plainly instead of failing
with a driver error.

---

## Files (GridFS)

Opens the same two-pane commander used for SFTP and the object stores, with your database's GridFS
buckets on one side and local disk on the other — drag to upload or download, with the transfer
queue, quick-view and rename that come with it. Filenames inside a bucket are flat: a `/` in a name
is part of the name, not a folder.

---

## Aggregation pipeline builder

**Pipeline…** builds a pipeline stage by stage. **Preview stages** then runs it one stage at a time
and reports what survives each: the document count, how it moved (`$match — 240 docs (−12,191)`), and
a sample of the output. The stage where the count reaches zero is flagged, and a stage that errors
stops the walk and shows the server's own message — which is the whole reason to build a pipeline
this way rather than running six stages blind.

---

## Import, export and compare

**Export ▸**

- **Export JSON… / CSV…** — the documents currently on screen.
- **Export whole collection…** — JSON array, JSON lines or CSV, **streamed** to the file, so a
  collection larger than your heap still exports. The current filter is offered as the export filter.
- **Import into collection…** — JSON arrays and JSON lines are told apart automatically; CSV gets a
  field-mapping table where dotted headers (`address.city`) rebuild nested documents, with type
  inference (numbers, booleans, ISO dates, embedded JSON) and a live preview of the first document.
- **Compare with another collection…** — matches documents on `_id` and reports which side each is
  on, or **which fields differ**. The generated sync script (inserts and replaces, with deletes
  commented out) can be copied or sent to the Shell tab. Nothing is ever applied for you.

---

## Server panel

**Server…** opens three tabs:

- **Topology** — replica-set members with role, health and **seconds behind primary**, or a sharded
  cluster's shards. A standalone says so.
- **Operations** — `currentOp` filtered by how long an operation has been running, longest first,
  with kill-by-opid behind a confirm.
- **Profiler** — the profiler level and slow-ms threshold, and the slowest profiled operations.

---

## Safety

`updateMany` and `deleteMany` count first and ask: *"Update 12,431 document(s) in orders?"*, with the
filter shown. An **unfiltered** write — the `deleteMany({})` that empties a collection — additionally
requires typing the collection name before the button enables.

Structure changes (create/drop collection, create/drop index) and user administration live under
**Structure ▸** and **Auth…**.
