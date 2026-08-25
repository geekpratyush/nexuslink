# Spec — SQL client: Oracle SQL Developer parity and beyond

**Date:** 2026-08-25 · **Status:** audit + backlog · **Tracks:** `TASKS.md` §8.1.2

Goal: a user who lives in SQL Developer should find nothing missing, and several things they can't
get there. This is an audit of the actual code as of 2026-08-25, not an aspiration.

## Already at or beyond parity

| Capability | Where |
|------------|-------|
| SQL editor: highlighting, `Ctrl+Space` completion, formatter | `SqlHighlighter`, `SqlFormatter`, `SqlTokenizer` |
| Run all / run selection / run at caret; multi-statement scripts | `SqlScriptSplitter` |
| Result grid: sortable, live filter, JSON/CSV export | `ResultGridExporter` |
| Editable grid — cell edit, insert row, delete row, all preview-then-apply | `SqlClientView` |
| **Single-record form view** with navigation and editing | `RecordFormDialog` |
| **Bind `:name` / substitution `&name` / sticky `&&name`** with prompt | `SqlBindVariables` |
| Transactions: auto-commit toggle, commit, rollback | `JdbcService` |
| Schema browser: tables/views/procedures/functions → columns, indexes, FKs | `JdbcExplorer` |
| DDL: create table/index, add/rename/drop column, drop table/view | `SqlClientView` |
| Structure/DDL export for selected objects | `SchemaExporter` |
| Explain plan | `SqlClientView` |
| CSV import with column mapping, as one transaction | `CsvImportPlanner` |
| Connection pooling, driver-specific TLS, query history | `JdbcConnectionPool`, `JdbcTlsParams` |
| **Beyond SQL Developer:** ER diagram with SVG/PNG export; visual query builder; result charting; code generation; any-JDBC-database (not Oracle-first); `${VAR}` environments shared with every other protocol; unified cross-protocol history; the driver manager above | — |

## Gaps, prioritised

### P1 — regularly used, clearly missing

1. **Execute a stored procedure/function with parameters.** The tree lists them; there is no way to
   call one. Needs an `IN`/`OUT`/`INOUT` parameter form over `CallableStatement`, rendering `OUT`
   values and any returned cursor.
2. **Server output (`DBMS_OUTPUT`).** A panel that enables output on the session and drains it after
   each execution. Without it, PL/SQL debugging by print is impossible.
3. **Export in more formats.** Only JSON/CSV today. Add `INSERT` statements, delimited-with-options,
   XML and HTML; export a whole table, not just the displayed rows.
4. **In-tab SQL history** with recall into the editor. History is recorded globally but there is no
   per-connection recall panel.
5. **Grid quality of life:** aggregate footer (count/sum/avg of a selected column), column freeze,
   find-in-results, and a "copy as INSERT / as Markdown" menu.

### P2 — expected by heavy users

6. **PL/SQL and stored-program editing** — open a package/procedure body, edit, compile, and surface
   compilation errors against line numbers.
7. **Bind variable typing and NULL.** The prompt currently binds every value as a string and has no
   way to say NULL. Add a type selector and a NULL checkbox per parameter.
8. **Sessions / locks panel** for DBAs (server-specific queries per dialect).
9. **Snippets library** — user-defined, insertable SQL fragments.
10. **Multiple pinned result tabs** instead of a single replaced grid.
11. **Schema compare / diff** between two connections.

### P3 — deliberately deferred

12. **PL/SQL debugger** (breakpoints, stepping). Large, Oracle-specific, needs `DBMS_DEBUG`.
13. **Data Modeler.** A separate product inside SQL Developer; the ER diagram covers the common need.
14. **User-defined reports** and the **unit-test framework**.
15. **Scheduler/job management** — excluded by the mission's non-goals.

## Out of scope permanently

Anything requiring the app to run as a service, or Oracle-only behaviour that cannot degrade
sensibly on the other twelve catalogued databases.
