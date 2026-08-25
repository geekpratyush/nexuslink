# Spec — SQL client: the editor and the things no SQL client has built

**Date:** 2026-08-25 · **Status:** design backlog · **Tracks:** `TASKS.md` §10.2 · **Prefix:** `SQLX-`

Companion to `2026-08-25-sql-developer-parity/` — that spec closes the gap against SQL Developer and
DBeaver (stored procedures, `DBMS_OUTPUT`, export formats, grid polish). **This spec is the other
half: the editor experience, and five capabilities that no shipped SQL client offers.** Building only
the parity spec yields a good clone; building this one is why someone switches.

## Editor — what a modern SQL editor has that ours does not

Built today: highlighting (`SqlHighlighter`), `Ctrl+Space` completion, formatter (`SqlFormatter`),
tokenizer (`SqlTokenizer`), script splitting, bind/substitution variables (`SqlBindVariables`),
run-all / run-selection / run-at-caret, explain plan, editable grid with preview-then-apply.

- [ ] **SQLX-E1 Multiple pinned result tabs** — each execution opens a tab (pin to keep), rather than
      replacing the single grid. Prerequisite for comparing two queries side by side.
- [ ] **SQLX-E2 Context-aware completion** — alias-aware column completion (`o.` after
      `FROM orders o`), `JOIN … ON` suggestions derived from the real foreign keys `JdbcExplorer`
      already reads, and completion inside `INSERT` column lists.
- [ ] **SQLX-E3 Inline error markers** — on a failed execution, map the driver's error position back to
      the statement and underline it in the editor instead of only printing the message.
- [ ] **SQLX-E4 Snippets library** — user-defined, parameterised fragments, stored in
      `~/.nexuslink/sql-snippets.json`, insertable by prefix + `Tab`.
- [ ] **SQLX-E5 Multi-caret / column selection editing**, block comment toggle, duplicate line, move line —
      the ordinary modern-editor set the RichTextFX area does not give for free.
- [ ] **SQLX-E6 Statement outline / minimap** for long scripts, driven by `SqlScriptSplitter`.
- [ ] **SQLX-E7 Per-tab local history of *executions*** with recall (distinct from the global history
      store): statement, timing, row count, connection, re-run.
- [ ] **SQLX-E8 Typed bind variables** — a type selector and an explicit NULL per parameter. Today every
      value binds as a string, which is wrong for dates and numerics on strict drivers.

## The five that are not built anywhere

### SQLX-1 — Undo for DML, and blast radius before it

- [ ] **Blast-radius preview**: before an `UPDATE`/`DELETE` runs, derive the matching `SELECT count(*)`
      from the parsed statement and show "this changes 4,812 rows" with the sample rows.
- [ ] **Compensating script**: capture the affected rows *before* applying (bounded by a configurable row
      cap), and generate the `UPDATE`/`INSERT` that puts them back.
- [ ] **Undo last statement** button that runs it, inside one transaction, with the same
      preview-then-apply gate the editable grid already uses.
- [ ] Degrade honestly: when the row set is too large to snapshot, or the table has no unique key, say
      **why** undo is unavailable rather than silently offering nothing.

Every client lets you fire the gun; none hands it to you with a safety. Highest value per unit of work
in this document, and it reuses machinery that already exists.

### SQLX-2 — Environment-governed execution

- [ ] Tag a connection `dev` / `stage` / `prod` (the environment model and profile properties already exist).
- [ ] Production defaults: **read-only**, DDL blocked, typed confirmation to unlock for a session.
- [ ] Refuse `UPDATE`/`DELETE` with no `WHERE` unless explicitly overridden (parse, don't regex).
- [ ] Colour the tab and the status bar by environment, everywhere — not just SQL.
- [ ] Shared with Mongo (`MG-14`), Redis (`MSG-R3`) and IBM MQ destructive operations.

### SQLX-3 — Cross-protocol SQL (the flagship)

- [ ] Register any protocol result as a **virtual table**: a JDBC query, a Mongo collection, a Kafka topic
      window, a REST endpoint's JSON, a CSV in S3.
- [ ] Materialise each into the **already-bundled H2** as a temp table, then run ordinary SQL across them:
      `SELECT … FROM pg.orders o JOIN rest.customers c ON c.id = o.customer_id`.
- [ ] Explicit, visible materialisation: row caps, a refresh button, and a clear statement of what was
      pulled locally — never a silent unbounded fetch.
- [ ] Ships as its own connection type ("Virtual") so it does not complicate the normal SQL path.

"Join my production table against the API that is supposed to agree with it" is a daily question with no
good tool. Nothing else can copy this without first building the other twelve protocol clients.

### SQLX-4 — Reproducible query notebooks

- [ ] A `.nlsql` document: ordered cells of SQL + captured results + timings + the environment used.
- [ ] Re-run a whole notebook against a different connection/environment; diff the results against the
      captured ones.
- [ ] Charts (the `ui.chart` code exists) and Markdown notes inline; file-based, so it lives in the team's
      own repo — collaboration stays file-based per the mission.

### SQLX-5 — Query performance drift

- [ ] Persist per-statement timings (history is already recorded centrally) keyed by a normalised
      statement fingerprint from `SqlTokenizer` (literals stripped).
- [ ] Chart the trend and flag regressions: "this query is 4× slower than last Tuesday."
- [ ] Attach the plan captured at each point so the regression can be explained, not just observed.

### SQLX-6 — Schema-aware assist over the org's own LLM gateway

- [ ] Explain a plan in English, suggest indexes, generate SQL from the schema — through the existing
      `LlmEndpointConfig` (internal gateway + OIDC already solved in `2026-08-25-llm-endpoints/`).
- [ ] **Schema is sent; data rows are never sent by default.** Opt-in per connection, off on `prod`,
      with a visible statement of exactly what left the machine.

The differentiator is not the model — it is that this works inside a locked-down network, which is the
product's whole thesis.

## Build order

`SQLX-1` → `SQLX-2` → `SQLX-E1`/`E7` → `SQLX-E2` → `SQLX-5` → `SQLX-3` (multi-session, needs its own
design pass) → `SQLX-4` → `SQLX-6`. Editor items `E3`–`E6`, `E8` are cheap fillers between the big ones.
