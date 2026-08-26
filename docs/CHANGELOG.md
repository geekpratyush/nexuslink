# Changelog

What has shipped, newest first. Roadmap items live in `TASKS.md` and `.agent-os/specs/`; this file
records only work that is in the code and verified.

---

## 2026-08-26

### Distribution and packaging

- **`dist/publish.sh` — one publish command** that behaves the same on a laptop and in CI: build,
  install to `~/.m2` always, then deploy if a repository is configured (`--local` never deploys,
  `--deploy` insists on one). CI examples for GitHub Actions and Jenkins are in `DISTRIBUTION.md`.
- **Three launchers**: `nexuslink.sh`, `nexuslink.bat` and `nexuslink.ps1`, with `--fresh`
  (clear then re-download), `--clean` (empty the cache, or one version), `--local` (run the `~/.m2`
  build with no repository at all) and a real `--help` listing every option and setting. Point
  `NEXUSLINK_HOME` at a relative path to keep the download in the current folder.
- **A GitHub Pages site** in `docs/` — `.nojekyll`, a self-contained `index.html`, and
  `docs/downloads/` holding the three launchers, refreshed from `dist/` by `publish.sh`.

- **Install from your own Artifactory, without the source.** `mvn -Pfatjar,fatjar-all-platforms,
  publish … deploy` publishes the self-contained JAR as the `all` classifier; users get one script
  (`dist/nexuslink.sh` / `dist/nexuslink.cmd`) that downloads it once, caches it under
  `~/.nexuslink/runtime`, and runs offline thereafter. `--update`, `--offline`, `--version`,
  `--list`, `--where`. Resolves `RELEASE`/`LATEST` and timestamped `-SNAPSHOT` filenames, verifies
  the repository's `.sha256` or `.sha1`, and never leaves a partial download in the cache.
  See `DISTRIBUTION.md`.
- The main window now opens **maximized**.

### Security and presentation of credentials

- **Connection strings are chips, not text boxes.** A collapsed chip shows the connection's name or a
  short `host:port/database` label; double-click to edit. Credentials inside the string are hidden
  while collapsed, including in the tooltip and the default Copy action. Applied to the Mongo
  connection string and the SQL JDBC URL, which also gives the toolbar its space back.
- **Secrets that were plain text are masked** with a reveal toggle: REST bearer token, API-key value
  and AWS session token; the LLM endpoint dialog's API key and client secret. Everything else was
  already using a password field — audited across every client.
- **The activity log no longer prints credentials** in connect lines.
- Pure `UriRedactor` in core handles userinfo (`scheme://user:pass@`) and `password=`/`token=`/
  `secret=`/`apikey=` parameters in query strings and JDBC property lists.

### Diagnostics

- **Driver warnings now reach the Activity log.** NexusLink ships an SLF4J binding and forwards
  library warnings (failed SASL handshake, unavailable Kafka leader, connection reset) into the
  in-app log. Previously SLF4J installed a no-op logger and discarded all of it.
- `RUN.md` documents the `glXCreateNewContext` message: not an application error, JavaFX falling back
  to software rendering, with the opt-out for anyone who wants the line gone.

### MongoDB client — Compass / Studio 3T parity (all P1 and P2 items)

- **Tree view** with real BSON types, now the default, beside JSON / Table / Schema / Indexes.
- **Typed in-place editing** — the type is chosen explicitly, so a save cannot silently turn an Int64
  into a Double; the update is a `$set` on that field's path for that `_id`.
- **`mongosh`-style shell** with history — a grammar parser, not a JavaScript engine, and it says so.
- **Query bar** with projection, sort, skip and per-collection saved queries.
- **Schema analyser** (`$sample`) calling out optional and mixed-type fields.
- **Index usage and advice** from `$indexStats`, including the index the current query would want.
- **Streaming collection import/export** — JSON array, JSON lines, CSV with field mapping and type
  inference.
- **Stage-by-stage pipeline preview** showing the count after each stage and where documents run out.
- **Change streams** panel, **GridFS browser** through the existing two-pane commander,
  **collection compare** with a generated (never applied) sync script, **server panel**
  (topology, `currentOp` with kill, profiler), **readable explain**, and a **bulk-write guardrail**
  that counts first and requires typing the collection name for an unfiltered delete.
- Deployment-aware behaviour: product, version and topology are read from the server, `collStats`
  uses the `$collStats` aggregation on 6.2+, and the wire-compatible imitations are recognised.

### Kafka client — AKHQ / Conduktor parity (all P1 items)

- **Produce** with headers, target partition, explicit timestamp and real **tombstones**.
- **Headers and tombstones on browse/consume**, with a Show headers dialog and header-aware filters.
- **Avro and JSON Schema decoding** through the Schema Registry — an Avro topic renders as JSON
  instead of mojibake.
- **Consumer-group administration**, **cluster panel**, **topic configuration editing**
  (`incrementalAlterConfigs`, diff-previewed), and **seek + replay** to another topic.
- Fixed: `stopConsuming()` left the consumer in its group until the broker's session timeout.

### REST client — Postman parity (all P1 items)

- **Collection runner**: iterations, CSV/JSON data file, delay, stop-on-failure, pass/fail report.
- **Post-response extraction** (JSON path / header / regex / status) into session-scoped variables —
  what makes login-then-call chains work.
- **Form-data body UI** with a file part per row, and a **binary** body that sends a file as-is.
- **Save response to file**, bytes as received.

### SQL client — SQL Developer parity (all P1 items)

- **Dialect-correct generated SQL** — row caps, quoting, truncate, renames, add-column and view
  replacement all follow the connected engine (Oracle, SQL Server, MySQL, Postgres, SQLite, H2, Db2).
- **Stored procedures and functions** with an IN/OUT parameter form built from database metadata.
- **Object panel** shows a routine's signature, parameters and source; uniform Create/Alter/Drop
  menus across tables, views, routines, columns and folders.
- **Exports** as INSERT statements, XML, HTML and delimited-with-options, plus whole-table export.
- **Server Output** (`DBMS_OUTPUT` and notices), **in-tab statement history**, and a results grid
  with an aggregate footer, column freeze, find-in-results and copy-as-INSERT/Markdown.
- Irreversible statements (DROP/TRUNCATE/DELETE) require an explicit confirmation tick.

### Redis client

- The console **dispatches any command the server supports** instead of matching a hardcoded list,
  with redis-cli quoting and a reply renderer that handles every RESP2/RESP3 shape. Version gaps are
  explained ("GETDEL needs Redis 6.2; this server reports 6.0.16").

### Workspace

- **Sidebar search**: type-to-filter over saved connections and samples (name, protocol, host, user),
  and a connection-type filter that knows the everyday word (`postgres`, `queue`, `bucket`).
  **Ctrl+K** focuses it.
- The **Connection menu** — previously empty — now holds Search, Open selected, Import/Export
  connections and Restore hidden samples.
- Help gained **Menus & Toolbars**, **MongoDB Client** and **Installing & Updating** topics; the
  keyboard-shortcut reference was rewritten to list only shortcuts that actually exist.
